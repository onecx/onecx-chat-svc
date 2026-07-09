package org.tkit.onecx.chat.rs.internal.services;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.tkit.onecx.chat.domain.daos.MessageDAO;
import org.tkit.onecx.chat.domain.models.Chat;
import org.tkit.onecx.chat.domain.models.Message;
import org.tkit.onecx.chat.rs.internal.mappers.ChatMapper;

import gen.io.github.onecx.ai.clients.model.AgentFilter;
import gen.io.github.onecx.ai.clients.model.RequestContext;
import gen.org.tkit.onecx.chat.rs.internal.model.ConfigurationFilterDTO;
import gen.org.tkit.onecx.chat.rs.internal.model.CreateMessageDTO;
import gen.org.tkit.onecx.chat.rs.internal.model.RequestContextDTO;
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
        RequestContextDTO requestContextDTO = new RequestContextDTO();
        requestContextDTO.setAiContext(List.of("test"));
        requestContextDTO.setFilter(new ConfigurationFilterDTO().key(ConfigurationFilterDTO.KeyEnum.APP_ID).value("test"));
        dto.setRequestContext(requestContextDTO);

        var createdMessage = new Message();
        createdMessage.setId("msg-id");

        when(mapper.createMessage(dto)).thenReturn(createdMessage);
        when(msgDao.create(createdMessage)).thenReturn(createdMessage);

        RequestContext requestContext = new RequestContext();
        requestContext.setAiContext(List.of("test"));
        requestContext.setFilter(new AgentFilter().key(AgentFilter.KeyEnum.APP_ID).value("test"));
        when(mapper.mapContext(requestContextDTO)).thenReturn(requestContext);

        doThrow(new RuntimeException("boom"))
                .when(asyncAiProcessingService).process("chat-id", "msg-id", requestContext);

        var result = Assertions.assertDoesNotThrow(() -> service.createChatMessage(chat, dto));

        Assertions.assertNotNull(result);
        Assertions.assertEquals("msg-id", result.getId());
        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> verify(asyncAiProcessingService).process("chat-id", "msg-id", requestContext));
        verify(asyncAiProcessingService, never()).forwardToAiAndStore(any(), any(), any());
    }
}
