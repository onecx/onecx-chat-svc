package org.tkit.onecx.chat.rs.internal.services;

import java.util.ArrayList;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.tkit.onecx.chat.domain.daos.ChatDAO;
import org.tkit.onecx.chat.domain.daos.MessageDAO;
import org.tkit.onecx.chat.domain.models.Chat;
import org.tkit.onecx.chat.domain.models.Message;
import org.tkit.onecx.chat.domain.models.Participant;
import org.tkit.onecx.chat.rs.internal.mappers.ChatMapper;

import gen.io.github.onecx.ai.clients.api.DispatchApi;
import gen.io.github.onecx.ai.clients.model.ChatMessage;
import gen.io.github.onecx.ai.clients.model.ChatRequest;
import gen.io.github.onecx.ai.clients.model.Conversation;
import gen.io.github.onecx.notification.clients.api.NotificationV1Api;
import gen.io.github.onecx.notification.clients.model.ContentMeta;
import gen.io.github.onecx.notification.clients.model.Issuer;
import gen.io.github.onecx.notification.clients.model.Notification;
import gen.io.github.onecx.notification.clients.model.Severity;
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

    public void process(String chatId, String messageId) {
        var chat = chatDao.findById(chatId);
        var message = messageDao.findById(messageId);

        if (chat == null || message == null) {
            log.warn("Skipping async AI processing because chat or message was not found. chatId={}, messageId={}",
                    chatId, messageId);
            return;
        }

        forwardToAiAndStore(chat, message);
        notifyAsyncAiResponseReady(chat, message);
    }

    public void forwardToAiAndStore(Chat chat, Message message) {
        Conversation conversation = mapper.mapChat2Conversation(chat);
        ChatMessage chatMessage = mapper.mapMessage(message);

        ChatRequest chatRequest = new ChatRequest();
        chatRequest.chatMessage(chatMessage);
        chatRequest.conversation(conversation);

        try (Response response = dispatchClient.chat(chatRequest)) {
            var chatResponse = response.readEntity(ChatMessage.class);
            storeAiResponse(chat.getId(), chatResponse);
        }
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
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

    private void notifyAsyncAiResponseReady(Chat chat, Message message) {
        List<ContentMeta> contentMetaList = new ArrayList<>();
        contentMetaList.add(new ContentMeta().key("chatId").value(chat.getId()));
        contentMetaList.add(new ContentMeta().key("type").value("update_chat"));

        for (Participant participant : chat.getParticipants()) {
            if (message.getUserId().equals(participant.getUserId())) {
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

            Response ignored = notificationClient.dispatchNotification(notification);
            if (ignored != null) {
                ignored.close();
            }
        }
    }
}
