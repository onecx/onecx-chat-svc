package org.tkit.onecx.chat.rs.internal.services;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.HashSet;

import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.tkit.onecx.chat.domain.daos.ChatDAO;
import org.tkit.onecx.chat.domain.daos.MessageDAO;
import org.tkit.onecx.chat.domain.models.Chat;
import org.tkit.onecx.chat.domain.models.Message;
import org.tkit.onecx.chat.domain.models.Participant;
import org.tkit.onecx.chat.rs.internal.mappers.ChatMapper;

import gen.io.github.onecx.ai.clients.api.DispatchApi;
import gen.io.github.onecx.ai.clients.model.ChatMessage;
import gen.io.github.onecx.ai.clients.model.Conversation;
import gen.io.github.onecx.ai.clients.model.RequestContext;
import gen.io.github.onecx.notification.clients.api.NotificationV1Api;
import gen.io.github.onecx.notification.clients.model.Notification;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class AsyncAiProcessingServiceTest {

    @Inject
    AsyncAiProcessingService service;

    @InjectMock
    ChatDAO chatDao;

    @InjectMock
    MessageDAO messageDao;

    @InjectMock
    ChatMapper mapper;

    @InjectMock
    @RestClient
    DispatchApi dispatchClient;

    @InjectMock
    @RestClient
    NotificationV1Api notificationClient;

    @Test
    void processShouldReturnWhenChatNotFound() {
        when(chatDao.findById(anyString())).thenReturn(null);
        when(messageDao.findById(anyString())).thenReturn(new Message());

        service.process("chat-id", "message-id", new RequestContext());

        verifyNoInteractions(dispatchClient, notificationClient);
        verify(messageDao, never()).create(any(Message.class));
    }

    @Test
    void processShouldReturnWhenMessageNotFound() {
        when(chatDao.findById(anyString())).thenReturn(new Chat());
        when(messageDao.findById(anyString())).thenReturn(null);

        service.process("chat-id", "message-id", new RequestContext());

        verifyNoInteractions(dispatchClient, notificationClient);
        verify(messageDao, never()).create(any(Message.class));
    }

    @Test
    void processShouldNotifyOnlyNonSenderParticipants() {
        var chatId = "chat-id";
        var messageId = "message-id";
        var senderUser = "sender";
        var receiverUser = "receiver";

        var chat = new Chat();
        chat.setId(chatId);
        var sender = new Participant();
        sender.setUserId(senderUser);
        var receiver = new Participant();
        receiver.setUserId(receiverUser);
        chat.setParticipants(new HashSet<>());
        chat.getParticipants().add(sender);
        chat.getParticipants().add(receiver);

        var message = new Message();
        message.setId(messageId);
        message.setUserId(senderUser);

        var conversation = new Conversation();
        conversation.setConversationId(chatId);
        var requestMessage = new ChatMessage();
        requestMessage.setConversationId(messageId);
        var aiChatMessage = new ChatMessage();
        aiChatMessage.setConversationId("ai-msg");
        aiChatMessage.setMessage("AI response");
        aiChatMessage.setType(ChatMessage.TypeEnum.ASSISTANT);
        var persistedAiMessage = new Message();

        when(chatDao.findById(chatId)).thenReturn(chat);
        when(messageDao.findById(messageId)).thenReturn(message);
        when(mapper.mapChat2Conversation(chat)).thenReturn(conversation);
        when(mapper.mapMessage(message)).thenReturn(requestMessage);
        when(dispatchClient.chat(any())).thenReturn(Response.ok(aiChatMessage).build());
        when(mapper.mapAiSvcMessage(aiChatMessage)).thenReturn(persistedAiMessage);

        service.process(chatId, messageId, new RequestContext());

        verify(messageDao).create(persistedAiMessage);
        var notificationCaptor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationClient, times(1)).dispatchNotification(notificationCaptor.capture());

        var notification = notificationCaptor.getValue();
        Assertions.assertEquals(senderUser, notification.getSenderId());
        Assertions.assertEquals(receiverUser, notification.getReceiverId());
    }

    @Test
    void processShouldNotNotifyWhenOnlySenderParticipates() {
        var chatId = "chat-id";
        var messageId = "message-id";
        var senderUser = "sender";

        var chat = new Chat();
        chat.setId(chatId);
        var sender = new Participant();
        sender.setUserId(senderUser);
        chat.setParticipants(new HashSet<>());
        chat.getParticipants().add(sender);

        var message = new Message();
        message.setId(messageId);
        message.setUserId(senderUser);

        var conversation = new Conversation();
        conversation.setConversationId(chatId);
        var requestMessage = new ChatMessage();
        requestMessage.setConversationId(messageId);
        var aiChatMessage = new ChatMessage();
        aiChatMessage.setConversationId("ai-msg");
        aiChatMessage.setMessage("AI response");
        aiChatMessage.setType(ChatMessage.TypeEnum.ASSISTANT);
        var persistedAiMessage = new Message();

        when(chatDao.findById(chatId)).thenReturn(chat);
        when(messageDao.findById(messageId)).thenReturn(message);
        when(mapper.mapChat2Conversation(chat)).thenReturn(conversation);
        when(mapper.mapMessage(message)).thenReturn(requestMessage);
        when(dispatchClient.chat(any())).thenReturn(Response.ok(aiChatMessage).build());
        when(mapper.mapAiSvcMessage(aiChatMessage)).thenReturn(persistedAiMessage);

        service.process(chatId, messageId, new RequestContext());

        verify(messageDao).create(persistedAiMessage);
        verify(notificationClient, never()).dispatchNotification(any(Notification.class));
    }

    @Test
    void processShouldCloseNotificationResponseWhenPresent() {
        var chatId = "chat-id";
        var messageId = "message-id";
        var senderUser = "sender";
        var receiverUser = "receiver";

        var chat = new Chat();
        chat.setId(chatId);
        var sender = new Participant();
        sender.setUserId(senderUser);
        var receiver = new Participant();
        receiver.setUserId(receiverUser);
        chat.setParticipants(new HashSet<>());
        chat.getParticipants().add(sender);
        chat.getParticipants().add(receiver);

        var message = new Message();
        message.setId(messageId);
        message.setUserId(senderUser);

        var conversation = new Conversation();
        conversation.setConversationId(chatId);
        var requestMessage = new ChatMessage();
        requestMessage.setConversationId(messageId);
        var aiChatMessage = new ChatMessage();
        aiChatMessage.setConversationId("ai-msg");
        aiChatMessage.setMessage("AI response");
        aiChatMessage.setType(ChatMessage.TypeEnum.ASSISTANT);
        var persistedAiMessage = new Message();
        var notificationResponse = mock(Response.class);

        when(chatDao.findById(chatId)).thenReturn(chat);
        when(messageDao.findById(messageId)).thenReturn(message);
        when(mapper.mapChat2Conversation(chat)).thenReturn(conversation);
        when(mapper.mapMessage(message)).thenReturn(requestMessage);
        when(dispatchClient.chat(any())).thenReturn(Response.ok(aiChatMessage).build());
        when(mapper.mapAiSvcMessage(aiChatMessage)).thenReturn(persistedAiMessage);
        when(notificationClient.dispatchNotification(any(Notification.class))).thenReturn(notificationResponse);

        service.process(chatId, messageId, new RequestContext());

        verify(notificationClient).dispatchNotification(any(Notification.class));
        verify(notificationResponse).close();
    }

    @Test
    void storeAiResponseShouldReturnWhenManagedChatNotFound() {
        var chatId = "missing-chat-id";
        var chatResponse = new ChatMessage();

        when(chatDao.findById(chatId)).thenReturn(null);

        Assertions.assertDoesNotThrow(() -> service.storeAiResponse(chatId, chatResponse));

        verify(mapper, never()).mapAiSvcMessage(any(ChatMessage.class));
        verify(messageDao, never()).create(any(Message.class));
    }
}
