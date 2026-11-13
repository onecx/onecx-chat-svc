package org.tkit.onecx.chat.rs.internal.services;

import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.tkit.onecx.chat.domain.daos.MessageDAO;
import org.tkit.onecx.chat.domain.models.Chat;
import org.tkit.onecx.chat.domain.models.ConversationEntry;
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
    ConversationEntryService conversationEntryService;

    @InjectMock
    Event<AsyncAiProcessingRequest> asyncAiProcessingRequestEvent;

    @Test
    void createChatMessageAsyncShouldPublishProcessingEventTest() {
        var apmPrincipalToken = "apm-token";
        var userAuthorization = "user-authz";
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
        createdMessage.setType(Message.MessageType.HUMAN);
        var entry = new ConversationEntry();

        when(mapper.createMessage(dto)).thenReturn(createdMessage);
        when(conversationEntryService.createTypedMessage(chat, createdMessage)).thenReturn(entry);
        when(mapper.mapEntryAsMessage(entry)).thenReturn(createdMessage);

        RequestContext requestContext = new RequestContext();
        requestContext.setAiContext(List.of("test"));
        requestContext.setFilter(new AgentFilter().key(AgentFilter.KeyEnum.APP_ID).value("test"));
        when(mapper.mapContext(requestContextDTO)).thenReturn(requestContext);

        var result = Assertions
                .assertDoesNotThrow(() -> service.createChatMessage(chat, dto, apmPrincipalToken, userAuthorization));

        Assertions.assertNotNull(result);
        Assertions.assertEquals("msg-id", result.getId());
        verify(conversationEntryService).createTypedMessage(chat, createdMessage);
        verify(msgDao, org.mockito.Mockito.never()).create(createdMessage);
        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> verify(asyncAiProcessingRequestEvent)
                        .fire(new AsyncAiProcessingRequest("chat-id", "msg-id", requestContext, apmPrincipalToken,
                                userAuthorization)));
    }

    @Test
    void nullRoleMessageKeepsLegacyPersistence() {
        var chat = new Chat();
        chat.setType(Chat.ChatType.HUMAN_DIRECT_CHAT);
        var dto = new CreateMessageDTO();
        dto.setSkipAIProcessing(true);
        var message = new Message();
        message.setText("Action");
        when(mapper.createMessage(dto)).thenReturn(message);
        when(msgDao.create(message)).thenReturn(message);

        var result = service.createChatMessage(chat, dto, null, null);

        Assertions.assertSame(message, result);
        Assertions.assertSame(chat, result.getChat());
        Assertions.assertEquals("Action", result.getText());
        verify(msgDao).create(message);
        org.mockito.Mockito.verifyNoInteractions(conversationEntryService, asyncAiProcessingRequestEvent);
    }
}
