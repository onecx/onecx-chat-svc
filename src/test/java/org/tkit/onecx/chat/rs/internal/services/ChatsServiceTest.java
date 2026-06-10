package org.tkit.onecx.chat.rs.internal.services;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.tkit.onecx.chat.domain.daos.MessageDAO;
import org.tkit.onecx.chat.domain.models.Chat;
import org.tkit.onecx.chat.domain.models.Message;
import org.tkit.onecx.chat.rs.internal.mappers.ChatMapper;

import gen.org.tkit.onecx.chat.rs.internal.model.CreateMessageDTO;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class ChatsServiceTest {

    @Inject
    ChatsService service;

    @InjectMock
    ChatMapper mapper;

    @InjectMock
    MessageDAO msgDao;

    @InjectMock
    AsyncAiProcessingService asyncAiProcessingService;

    @Test
    void createChatMessageAsyncShouldSwallowProcessingExceptionsTest() {
        var chat = new Chat();
        chat.setId("chat-id");
        chat.setType(Chat.ChatType.AI_CHAT);

        var dto = new CreateMessageDTO();
        dto.setType(gen.org.tkit.onecx.chat.rs.internal.model.MessageTypeDTO.HUMAN);
        dto.setText("question");
        dto.setUserId("user-1");
        dto.setAwaitResponse(false);
        dto.setSkipAIProcessing(false);

        var createdMessage = new Message();
        createdMessage.setId("msg-id");

        when(mapper.createMessage(dto)).thenReturn(createdMessage);
        when(msgDao.create(createdMessage)).thenReturn(createdMessage);

        doThrow(new RuntimeException("boom"))
                .when(asyncAiProcessingService).process("chat-id", "msg-id");

        var result = Assertions.assertDoesNotThrow(() -> service.createChatMessage(chat, dto));

        Assertions.assertNotNull(result);
        Assertions.assertEquals("msg-id", result.getId());
        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> verify(asyncAiProcessingService).process("chat-id", "msg-id"));
        verify(asyncAiProcessingService, never()).forwardToAiAndStore(any(), any());
    }
}
