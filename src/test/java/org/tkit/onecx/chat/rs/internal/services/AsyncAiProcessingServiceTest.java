package org.tkit.onecx.chat.rs.internal.services;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.inject.Inject;

import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.Test;
import org.tkit.onecx.chat.domain.daos.ChatDAO;
import org.tkit.onecx.chat.domain.daos.MessageDAO;
import org.tkit.onecx.chat.domain.models.Chat;
import org.tkit.onecx.chat.domain.models.Message;

import gen.io.github.onecx.ai.clients.api.DispatchApi;
import gen.io.github.onecx.notification.clients.api.NotificationV1Api;
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
    @RestClient
    DispatchApi dispatchClient;

    @InjectMock
    @RestClient
    NotificationV1Api notificationClient;

    @Test
    void processShouldReturnWhenChatNotFound() {
        when(chatDao.findById(anyString())).thenReturn(null);
        when(messageDao.findById(anyString())).thenReturn(new Message());

        service.process("chat-id", "message-id");

        verifyNoInteractions(dispatchClient, notificationClient);
        verify(messageDao, never()).create(any(Message.class));
    }

    @Test
    void processShouldReturnWhenMessageNotFound() {
        when(chatDao.findById(anyString())).thenReturn(new Chat());
        when(messageDao.findById(anyString())).thenReturn(null);

        service.process("chat-id", "message-id");

        verifyNoInteractions(dispatchClient, notificationClient);
        verify(messageDao, never()).create(any(Message.class));
    }
}
