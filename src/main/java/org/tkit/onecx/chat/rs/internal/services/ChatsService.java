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
import org.tkit.onecx.chat.domain.daos.ParticipantDAO;
import org.tkit.onecx.chat.domain.models.Chat;
import org.tkit.onecx.chat.domain.models.Message;
import org.tkit.onecx.chat.domain.models.Participant;
import org.tkit.onecx.chat.rs.internal.mappers.ChatMapper;
import org.tkit.onecx.chat.rs.internal.mappers.ExceptionMapper;

import gen.io.github.onecx.ai.clients.api.DispatchApi;
import gen.io.github.onecx.ai.clients.model.ChatMessage;
import gen.io.github.onecx.ai.clients.model.ChatRequest;
import gen.io.github.onecx.ai.clients.model.Conversation;
import gen.io.github.onecx.notification.clients.api.NotificationV1Api;
import gen.io.github.onecx.notification.clients.model.ContentMeta;
import gen.io.github.onecx.notification.clients.model.Issuer;
import gen.io.github.onecx.notification.clients.model.Notification;
import gen.io.github.onecx.notification.clients.model.Severity;
import gen.org.tkit.onecx.chat.rs.internal.model.*;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.smallrye.mutiny.Uni;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Transactional(Transactional.TxType.NOT_SUPPORTED)
@ApplicationScoped
public class ChatsService {

    @Inject
    @RestClient
    DispatchApi dispatchClient;

    @Inject
    @RestClient
    NotificationV1Api notificationClient;

    @Inject
    ChatDAO dao;

    @Inject
    ParticipantDAO participantDao;

    @Inject
    MessageDAO msgDao;

    @Inject
    ChatMapper mapper;

    @Inject
    ExceptionMapper exceptionMapper;

    @Inject
    ParticipantService participantService;

    @Transactional
    public Chat createChat(CreateChatDTO createChatDTO) {
        var chat = mapper.create(createChatDTO);
        chat = dao.create(chat);

        if (!createChatDTO.getParticipants().isEmpty()) {
            for (ParticipantDTO participantDTO : createChatDTO.getParticipants()) {
                var chatParticipant = participantService.getNewOrUpdatedParticipant(participantDTO);
                chat.getParticipants().add(chatParticipant);
                chatParticipant.getChats().add(chat);
                participantDao.update(chatParticipant);
            }
        }
        chat = dao.update(chat);
        return chat;
    }

    @Transactional
    public void deleteChat(String id) {
        dao.deleteQueryById(id);
    }

    @Transactional
    public void updateChat(Chat chat, UpdateChatDTO updateChatDTO) {
        mapper.update(updateChatDTO, chat);
        dao.update(chat);
    }

    @Transactional
    public Message createChatMessage(Chat chat, CreateMessageDTO createMessageDTO) {
        var message = mapper.createMessage(createMessageDTO);
        message.setChat(chat);
        message = msgDao.create(message);
        boolean skipAiProcessing = Boolean.TRUE.equals(createMessageDTO.getSkipAIProcessing());
        boolean awaitResponse = !Boolean.FALSE.equals(createMessageDTO.getAwaitResponse());

        if (shouldForwardToAiService(chat.getType(), skipAiProcessing)) {
            if (awaitResponse) {
                forwardToAiAndStore(chat, message);
            } else {
                var chatId = chat.getId();
                var messageId = message.getId();
                Uni.createFrom().voidItem()
                        .chain(() -> Uni.createFrom().voidItem()
                                .runSubscriptionOn(r -> QuarkusTransaction.requiringNew()
                                        .run(() -> {
                                            var managedChat = dao.findById(chatId);
                                            var managedMessage = msgDao.findById(messageId);
                                            if (managedChat == null || managedMessage == null) {
                                                log.warn(
                                                        "Skipping async AI processing because chat or message was not found. chatId={}, messageId={}",
                                                        chatId, messageId);
                                                return;
                                            }
                                            forwardToAiAndStore(managedChat, managedMessage);
                                            notifyAsyncAiResponseReady(managedChat, managedMessage);
                                        })))
                        .subscribe()
                        .with(item -> log.debug("Async AI processing completed for chatId={}", chatId),
                                ex -> log.error("Async AI response processing failed for chatId={}, messageId={}",
                                        chatId, messageId, ex));
            }
        }
        return message;
    }

    private void forwardToAiAndStore(Chat chat, Message message) {
        Conversation conversation = mapper.mapChat2Conversation(chat);
        ChatMessage chatMessage = mapper.mapMessage(message);

        ChatRequest chatRequest = new ChatRequest();
        chatRequest.chatMessage(chatMessage);
        chatRequest.conversation(conversation);

        try (Response response = dispatchClient.chat(chatRequest)) {
            var chatResponse = response.readEntity(ChatMessage.class);
            var responseMessage = mapper.mapAiSvcMessage(chatResponse);
            responseMessage.setChat(chat);
            msgDao.create(responseMessage);
        }
    }

    private void notifyAsyncAiResponseReady(Chat chat, Message message) {
        List<ContentMeta> contentMetaList = new ArrayList<>();
        contentMetaList.add(new ContentMeta().key("chatId").value(chat.getId()));
        contentMetaList.add(new ContentMeta().key("type").value("update_chat"));
        for (Participant participant : chat.getParticipants()) {
            if (message.getUserId().equals(participant.getUserId())) {
                continue; // Skip notifying the sender of the message
            }
            var notification = new Notification()
                    .issuer(Issuer.USER)
                    .applicationId("onecx-chat")
                    .senderId(message.getUserId())
                    .receiverId(participant.getUserId())
                    .persist(false)
                    .severity(Severity.NORMAL)
                    .contentMeta(contentMetaList);
            notificationClient.dispatchNotification(notification);
        }
    }

    @Transactional
    public Participant addParticipant(Chat chat, AddParticipantDTO addParticipantDTO) {
        var participant = participantService.getNewOrUpdatedParticipant(addParticipantDTO);
        if (participant.getChats().contains(chat)) {
            return participant;
        }
        participant.getChats().add(chat);
        chat.getParticipants().add(participant);
        participant = participantDao.update(participant);
        dao.update(chat);
        return participant;
    }

    private boolean shouldForwardToAiService(final Chat.ChatType chatType, final boolean skipProcessing) {
        return chatType.equals(Chat.ChatType.AI_CHAT) && !skipProcessing;
    }
}
