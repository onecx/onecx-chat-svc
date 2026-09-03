package org.tkit.onecx.chat.rs.internal.services;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.context.ManagedExecutor;
import org.eclipse.microprofile.context.ThreadContext;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.tkit.onecx.chat.domain.daos.ChatDAO;
import org.tkit.onecx.chat.domain.daos.MessageDAO;
import org.tkit.onecx.chat.domain.models.Chat;
import org.tkit.onecx.chat.domain.models.Message;
import org.tkit.onecx.chat.domain.models.Participant;
import org.tkit.onecx.chat.rs.internal.clients.AiServiceUserAuthorizationContext;
import org.tkit.onecx.chat.rs.internal.clients.ApmPrincipalTokenContext;
import org.tkit.onecx.chat.rs.internal.mappers.ChatMapper;

import gen.io.github.onecx.ai.clients.api.DispatchApi;
import gen.io.github.onecx.ai.clients.model.ChatMessage;
import gen.io.github.onecx.ai.clients.model.ChatRequest;
import gen.io.github.onecx.ai.clients.model.Conversation;
import gen.io.github.onecx.ai.clients.model.RequestContext;
import gen.io.github.onecx.notification.clients.api.NotificationV1Api;
import gen.io.github.onecx.notification.clients.model.ContentMeta;
import gen.io.github.onecx.notification.clients.model.Issuer;
import gen.io.github.onecx.notification.clients.model.Notification;
import gen.io.github.onecx.notification.clients.model.Severity;
import io.smallrye.context.api.ManagedExecutorConfig;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ApplicationScoped
public class AsyncAiProcessingService {

    @Inject
    ChatDAO chatDao;

    @Inject
    MessageDAO messageDao;

    @Inject
    ChatMapper mapper;

    @Inject
    @RestClient
    DispatchApi dispatchClient;

    @Inject
    @RestClient
    NotificationV1Api notificationClient;

    @Inject
    @ManagedExecutorConfig(propagated = ThreadContext.ALL_REMAINING, cleared = ThreadContext.TRANSACTION)
    ManagedExecutor managedExecutor;

    public void onAsyncAiProcessingRequested(
            @Observes(during = TransactionPhase.AFTER_SUCCESS) AsyncAiProcessingRequest request) {
        managedExecutor.runAsync(() -> {
            try {
                process(request.chatId(), request.messageId(), request.context(), request.apmPrincipalToken(),
                        request.userAuthorization());
                log.debug("Async AI processing completed for chatId={}", request.chatId());
            } catch (Exception ex) {
                log.error("Async AI response processing failed for chatId={}, messageId={}", request.chatId(),
                        request.messageId(), ex);
            }
        });
    }

    @ActivateRequestContext
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void process(String chatId, String messageId, RequestContext context, String apmPrincipalToken,
            String userAuthorization) {
        var chat = chatDao.findById(chatId);
        var message = messageDao.findById(messageId);

        if (chat == null || message == null) {
            log.warn("Skipping async AI processing because chat or message was not found. chatId={}, messageId={}",
                    chatId, messageId);
            return;
        }

        forwardToAiAndStore(chat, message, context, apmPrincipalToken, userAuthorization);
        notifyAsyncAiResponseReady(chat, message, apmPrincipalToken);
    }

    public void forwardToAiAndStore(Chat chat, Message message, RequestContext context, String apmPrincipalToken,
            String userAuthorization) {
        Conversation conversation = mapper.mapChat2Conversation(chat);
        ChatMessage chatMessage = mapper.mapMessage(message);

        ChatRequest chatRequest = new ChatRequest();
        chatRequest.chatMessage(chatMessage);
        chatRequest.conversation(conversation);
        chatRequest.setRequestContext(context);

        try (var ignored = ApmPrincipalTokenContext.withToken(apmPrincipalToken);
                var ignoredUserAuthorization = AiServiceUserAuthorizationContext.withHeader(userAuthorization);
                Response response = dispatchClient.chat(chatRequest)) {
            var chatResponse = response.readEntity(ChatMessage.class);
            storeAiResponse(chat.getId(), chatResponse);
        }
    }

    public void storeAiResponse(String chatId, ChatMessage chatResponse) {
        var managedChat = chatDao.findById(chatId);
        if (managedChat == null) {
            log.warn("Skipping AI response persistence because chat was not found. chatId={}", chatId);
            return;
        }

        var responseMessage = mapper.mapAiSvcMessage(chatResponse);
        responseMessage.setChat(managedChat);
        messageDao.create(responseMessage);
    }

    private void notifyAsyncAiResponseReady(Chat chat, Message message, String apmPrincipalToken) {
        try (var ignored = ApmPrincipalTokenContext.withToken(apmPrincipalToken)) {
            List<ContentMeta> contentMetaList = new ArrayList<>();
            contentMetaList.add(new ContentMeta().key("chatId").value(chat.getId()));
            contentMetaList.add(new ContentMeta().key("type").value("update_chat"));

            boolean notifyOriginalSender = Chat.ChatType.AI_CHAT.equals(chat.getType());
            for (Participant participant : chat.getParticipants()) {
                if (!notifyOriginalSender && Objects.equals(message.getUserId(), participant.getUserId())) {
                    continue;
                }

                var notification = new Notification()
                        .issuer(Issuer.USER)
                        .applicationId("onecx-chat")
                        .senderId(message.getUserId())
                        .receiverId(participant.getUserId())
                        .persist(false)
                        .severity(Severity.NORMAL)
                        .contentMeta(contentMetaList);

                try (Response _ = notificationClient.dispatchNotification(notification)) {
                    log.debug("Dispatched async AI response ready notification to user {} for chat {}",
                            participant.getUserId(), chat.getId());
                }
            }
        }
    }
}
