package org.tkit.onecx.chat.rs.internal.services;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.tkit.onecx.chat.domain.daos.ChatDAO;
import org.tkit.onecx.chat.domain.daos.ConversationEntryDAO;
import org.tkit.onecx.chat.domain.models.Chat;
import org.tkit.onecx.chat.domain.models.ConversationEntry;
import org.tkit.onecx.chat.rs.internal.mappers.ChatMapper;
import org.tkit.quarkus.jpa.exceptions.ConstraintException;
import org.tkit.quarkus.jpa.exceptions.DAOException;

import gen.org.tkit.onecx.chat.rs.internal.model.ConversationTypeDTO;
import gen.org.tkit.onecx.chat.rs.internal.model.CreateOrUpdateConversationEntryDTO;
import gen.org.tkit.onecx.chat.rs.internal.model.EntryStatusDTO;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class ConversationEntryServiceTest {

    @Inject
    ConversationEntryService service;

    @InjectMock
    ConversationEntryDAO dao;

    @InjectMock
    ChatDAO chatDAO;

    @InjectMock
    ChatMapper chatMapper;

    @Test
    void createOrUpdateShouldLoadExistingEntryAfterConstraintViolation() {

        Chat chat = new Chat();
        chat.setId("chat-1");

        CreateOrUpdateConversationEntryDTO dto = new CreateOrUpdateConversationEntryDTO();

        dto.setIdempotencyKey("idem-1");
        dto.setType(ConversationTypeDTO.HUMAN);
        dto.setText("Hello");
        dto.setStatus(EntryStatusDTO.IN_PROGRESS);

        ConversationEntry existing = new ConversationEntry();
        existing.setIdempotencyKey("idem-1");
        existing.setType(ConversationEntry.EntryType.HUMAN);
        existing.setText("Hello");
        existing.setStatus(ConversationEntry.EntryStatus.IN_PROGRESS);

        when(chatMapper.mapConversationStatus(dto.getStatus()))
                .thenReturn(ConversationEntry.EntryStatus.IN_PROGRESS);
        when(chatMapper.mapConversationType(dto.getType()))
                .thenReturn(ConversationEntry.EntryType.HUMAN);

        when(dao.findByChatAndIdempotencyKey(chat, "idem-1"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));

        when(dao.findMaxSequenceByChat(chat))
                .thenReturn(0L);

        ConstraintException exception = org.mockito.Mockito.mock(ConstraintException.class);

        when(dao.create(any(ConversationEntry.class)))
                .thenThrow(exception);

        ConversationEntry result = org.junit.jupiter.api.Assertions
                .assertDoesNotThrow(() -> service.createOrUpdate(chat, dto));

        assertSame(existing, result);

        verify(dao, times(2))
                .findByChatAndIdempotencyKey(chat, "idem-1");
    }

    @Test
    void createOrUpdateShouldRethrowDaoExceptionTest() {

        var chat = new Chat();
        chat.setId("chat-id");

        var dto = new CreateOrUpdateConversationEntryDTO();
        dto.setIdempotencyKey("key");
        dto.setType(ConversationTypeDTO.HUMAN);
        dto.setText("Hello");
        dto.setStatus(EntryStatusDTO.COMPLETED);

        when(chatMapper.mapConversationStatus(EntryStatusDTO.COMPLETED))
                .thenReturn(ConversationEntry.EntryStatus.COMPLETED);
        when(chatMapper.mapConversationType(ConversationTypeDTO.HUMAN))
                .thenReturn(ConversationEntry.EntryType.HUMAN);

        DAOException daoException = new DAOException(
                ConversationEntryDAO.ErrorKeys.ERROR_FIND_CONVERSATION_ENTRY_BY_IDEMPOTENCY_KEY,
                new Exception("test"));

        when(dao.findByChatAndIdempotencyKey(chat, "key"))
                .thenThrow(daoException);

        DAOException thrown = assertThrows(
                DAOException.class,
                () -> service.createOrUpdate(chat, dto));

        assertThat(thrown)
                .isSameAs(daoException);

        assertThat(thrown.getMessageKey())
                .isEqualTo(
                        ConversationEntryDAO.ErrorKeys.ERROR_FIND_CONVERSATION_ENTRY_BY_IDEMPOTENCY_KEY);
    }

    @Test
    void createOrUpdatePersistsAssistantTypeAndAgentConfigVersion() {
        var chat = new Chat();
        chat.setId("chat-id");

        var dto = new CreateOrUpdateConversationEntryDTO();
        dto.setIdempotencyKey("assistant-entry");
        dto.setType(ConversationTypeDTO.ASSISTANT);
        dto.setStatus(EntryStatusDTO.IN_PROGRESS);
        dto.setText("Hello");
        dto.setAgentConfigVersion("agent-config-1");

        when(chatMapper.mapConversationType(ConversationTypeDTO.ASSISTANT))
                .thenReturn(ConversationEntry.EntryType.ASSISTANT);
        when(chatMapper.mapConversationStatus(EntryStatusDTO.IN_PROGRESS))
                .thenReturn(ConversationEntry.EntryStatus.IN_PROGRESS);
        when(dao.findByChatAndIdempotencyKey(chat, "assistant-entry"))
                .thenReturn(Optional.empty());
        when(dao.findMaxSequenceByChat(chat)).thenReturn(0L);

        var result = service.createOrUpdate(chat, dto);

        assertThat(result.getType()).isEqualTo(ConversationEntry.EntryType.ASSISTANT);
        assertThat(result.getAgentConfigVersion()).isEqualTo("agent-config-1");
        assertThat(result.getSequence()).isEqualTo(1L);
    }

    @Test
    void createOrUpdateRejectsChangedEntryType() {
        var chat = new Chat();
        chat.setId("chat-id");

        var dto = new CreateOrUpdateConversationEntryDTO();
        dto.setIdempotencyKey("entry");
        dto.setType(ConversationTypeDTO.ASSISTANT);
        dto.setStatus(EntryStatusDTO.IN_PROGRESS);
        dto.setText("Hello");

        var existing = new ConversationEntry();
        existing.setIdempotencyKey("entry");
        existing.setType(ConversationEntry.EntryType.HUMAN);
        existing.setStatus(ConversationEntry.EntryStatus.IN_PROGRESS);
        existing.setText("Hello");

        when(chatMapper.mapConversationType(ConversationTypeDTO.ASSISTANT))
                .thenReturn(ConversationEntry.EntryType.ASSISTANT);
        when(chatMapper.mapConversationStatus(EntryStatusDTO.IN_PROGRESS))
                .thenReturn(ConversationEntry.EntryStatus.IN_PROGRESS);
        when(dao.findByChatAndIdempotencyKey(chat, "entry"))
                .thenReturn(Optional.of(existing));

        assertThrows(IdempotencyConflictException.class, () -> service.createOrUpdate(chat, dto));
    }

    @Test
    void createOrUpdateSetsAgentConfigVersionWhenItWasMissing() {
        var chat = new Chat();
        chat.setId("chat-id");
        var dto = assistantEntryRequest("entry", "agent-config-1");
        var existing = assistantEntry("entry", null);

        mockAssistantMappings();
        when(dao.findByChatAndIdempotencyKey(chat, "entry")).thenReturn(Optional.of(existing));
        when(dao.update(existing)).thenReturn(existing);

        var result = service.createOrUpdate(chat, dto);

        assertSame(existing, result);
        assertThat(existing.getAgentConfigVersion()).isEqualTo("agent-config-1");
        verify(dao).update(existing);
    }

    @Test
    void createOrUpdateTreatsOmittedAgentConfigVersionAsNoOp() {
        var chat = new Chat();
        chat.setId("chat-id");
        var dto = assistantEntryRequest("entry", null);
        var existing = assistantEntry("entry", "agent-config-1");

        mockAssistantMappings();
        when(dao.findByChatAndIdempotencyKey(chat, "entry")).thenReturn(Optional.of(existing));

        var result = service.createOrUpdate(chat, dto);

        assertSame(existing, result);
    }

    @Test
    void createOrUpdateTreatsMatchingAgentConfigVersionAsNoOp() {
        var chat = new Chat();
        chat.setId("chat-id");
        var dto = assistantEntryRequest("entry", "agent-config-1");
        var existing = assistantEntry("entry", "agent-config-1");

        mockAssistantMappings();
        when(dao.findByChatAndIdempotencyKey(chat, "entry")).thenReturn(Optional.of(existing));

        var result = service.createOrUpdate(chat, dto);

        assertSame(existing, result);
    }

    @Test
    void createOrUpdatePreservesSetAgentConfigVersionWhenTextGrows() {
        var chat = new Chat();
        chat.setId("chat-id");
        var dto = assistantEntryRequest("entry", "agent-config-1");
        dto.setText("Hello");
        var existing = assistantEntry("entry", "agent-config-1");
        existing.setText("Hel");

        mockAssistantMappings();
        when(dao.findByChatAndIdempotencyKey(chat, "entry")).thenReturn(Optional.of(existing));
        when(dao.update(existing)).thenReturn(existing);

        var result = service.createOrUpdate(chat, dto);

        assertSame(existing, result);
        assertThat(existing.getText()).isEqualTo("Hello");
        assertThat(existing.getAgentConfigVersion()).isEqualTo("agent-config-1");
    }

    @Test
    void createOrUpdateFinalizesEntryWithSetAgentConfigVersion() {
        var chat = new Chat();
        chat.setId("chat-id");
        var dto = assistantEntryRequest("entry", "agent-config-1");
        dto.setStatus(EntryStatusDTO.COMPLETED);
        var existing = assistantEntry("entry", "agent-config-1");

        when(chatMapper.mapConversationType(ConversationTypeDTO.ASSISTANT))
                .thenReturn(ConversationEntry.EntryType.ASSISTANT);
        when(chatMapper.mapConversationStatus(EntryStatusDTO.COMPLETED))
                .thenReturn(ConversationEntry.EntryStatus.COMPLETED);
        when(dao.findByChatAndIdempotencyKey(chat, "entry")).thenReturn(Optional.of(existing));
        when(dao.update(existing)).thenReturn(existing);

        var result = service.createOrUpdate(chat, dto);

        assertSame(existing, result);
        assertThat(existing.getStatus()).isEqualTo(ConversationEntry.EntryStatus.COMPLETED);
        assertThat(existing.getAgentConfigVersion()).isEqualTo("agent-config-1");
    }

    @Test
    void createOrUpdateRejectsChangedAgentConfigVersion() {
        var chat = new Chat();
        chat.setId("chat-id");
        var dto = assistantEntryRequest("entry", "agent-config-2");
        var existing = assistantEntry("entry", "agent-config-1");

        mockAssistantMappings();
        when(dao.findByChatAndIdempotencyKey(chat, "entry")).thenReturn(Optional.of(existing));

        assertThrows(IdempotencyConflictException.class, () -> service.createOrUpdate(chat, dto));
    }

    private static CreateOrUpdateConversationEntryDTO assistantEntryRequest(String idempotencyKey,
            String agentConfigVersion) {
        var dto = new CreateOrUpdateConversationEntryDTO();
        dto.setIdempotencyKey(idempotencyKey);
        dto.setType(ConversationTypeDTO.ASSISTANT);
        dto.setStatus(EntryStatusDTO.IN_PROGRESS);
        dto.setText("Hello");
        dto.setAgentConfigVersion(agentConfigVersion);
        return dto;
    }

    private static ConversationEntry assistantEntry(String idempotencyKey, String agentConfigVersion) {
        var entry = new ConversationEntry();
        entry.setIdempotencyKey(idempotencyKey);
        entry.setType(ConversationEntry.EntryType.ASSISTANT);
        entry.setStatus(ConversationEntry.EntryStatus.IN_PROGRESS);
        entry.setText("Hello");
        entry.setAgentConfigVersion(agentConfigVersion);
        return entry;
    }

    private void mockAssistantMappings() {
        when(chatMapper.mapConversationType(ConversationTypeDTO.ASSISTANT))
                .thenReturn(ConversationEntry.EntryType.ASSISTANT);
        when(chatMapper.mapConversationStatus(EntryStatusDTO.IN_PROGRESS))
                .thenReturn(ConversationEntry.EntryStatus.IN_PROGRESS);
    }
}
