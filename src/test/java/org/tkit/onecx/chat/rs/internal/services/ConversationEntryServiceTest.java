package org.tkit.onecx.chat.rs.internal.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.tkit.onecx.chat.domain.daos.ChatDAO;
import org.tkit.onecx.chat.domain.daos.ConversationEntryDAO;
import org.tkit.onecx.chat.domain.daos.MessageDAO;
import org.tkit.onecx.chat.domain.models.Chat;
import org.tkit.onecx.chat.domain.models.ConversationEntry;
import org.tkit.onecx.chat.domain.models.Message;
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

    @InjectMock
    MessageDAO msgDao;

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

    @Test
    void createOrUpdateStoresTerminalMessageOnlyAsConversationEntry() {
        var chat = new Chat();
        chat.setId("chat-id");

        var dto = new CreateOrUpdateConversationEntryDTO();
        dto.setIdempotencyKey("terminal-create");
        dto.setType(ConversationTypeDTO.HUMAN);
        dto.setStatus(EntryStatusDTO.COMPLETED);
        dto.setText("Hello");

        when(chatMapper.mapConversationType(ConversationTypeDTO.HUMAN))
                .thenReturn(ConversationEntry.EntryType.HUMAN);
        when(chatMapper.mapConversationStatus(EntryStatusDTO.COMPLETED))
                .thenReturn(ConversationEntry.EntryStatus.COMPLETED);
        when(dao.findByChatAndIdempotencyKey(chat, "terminal-create")).thenReturn(Optional.empty());
        when(dao.findMaxSequenceByChat(chat)).thenReturn(0L);

        service.createOrUpdate(chat, dto);

        var captor = ArgumentCaptor.forClass(ConversationEntry.class);
        verify(dao).create(captor.capture());
        var entry = captor.getValue();
        assertThat(entry.getType()).isEqualTo(ConversationEntry.EntryType.HUMAN);
        assertThat(entry.getStatus()).isEqualTo(ConversationEntry.EntryStatus.COMPLETED);
        assertThat(entry.getText()).isEqualTo("Hello");
        verify(msgDao, never()).create(any(Message.class));
    }

    @Test
    void createOrUpdateDoesNotCreateLegacyMessageWhenEntryTransitionsToCompleted() {
        var chat = new Chat();
        chat.setId("chat-id");
        var dto = assistantEntryRequest("entry", "agent-config-1");
        dto.setStatus(EntryStatusDTO.COMPLETED);
        var existing = assistantEntry("entry", "agent-config-1");
        existing.setChat(chat);

        when(chatMapper.mapConversationType(ConversationTypeDTO.ASSISTANT))
                .thenReturn(ConversationEntry.EntryType.ASSISTANT);
        when(chatMapper.mapConversationStatus(EntryStatusDTO.COMPLETED))
                .thenReturn(ConversationEntry.EntryStatus.COMPLETED);
        when(dao.findByChatAndIdempotencyKey(chat, "entry")).thenReturn(Optional.of(existing));
        when(dao.update(existing)).thenReturn(existing);

        service.createOrUpdate(chat, dto);

        verify(msgDao, never()).create(any(Message.class));
    }

    @Test
    void createOrUpdateDoesNotCreateLegacyMessageWhenEntryTransitionsToInterrupted() {
        var chat = new Chat();
        chat.setId("chat-id");
        var dto = assistantEntryRequest("entry", "agent-config-1");
        dto.setStatus(EntryStatusDTO.INTERRUPTED);
        var existing = assistantEntry("entry", "agent-config-1");
        existing.setChat(chat);

        when(chatMapper.mapConversationType(ConversationTypeDTO.ASSISTANT))
                .thenReturn(ConversationEntry.EntryType.ASSISTANT);
        when(chatMapper.mapConversationStatus(EntryStatusDTO.INTERRUPTED))
                .thenReturn(ConversationEntry.EntryStatus.INTERRUPTED);
        when(dao.findByChatAndIdempotencyKey(chat, "entry")).thenReturn(Optional.of(existing));
        when(dao.update(existing)).thenReturn(existing);

        service.createOrUpdate(chat, dto);

        verify(msgDao, never()).create(any(Message.class));
    }

    @Test
    void createOrUpdateNoOpRetryDoesNotCreateDuplicateMessage() {
        var chat = new Chat();
        chat.setId("chat-id");
        var dto = assistantEntryRequest("entry", "agent-config-1");
        dto.setStatus(EntryStatusDTO.COMPLETED);
        // The entry is already terminal with the same text: an identical retry is a no-op.
        var existing = assistantEntry("entry", "agent-config-1");
        existing.setStatus(ConversationEntry.EntryStatus.COMPLETED);

        when(chatMapper.mapConversationType(ConversationTypeDTO.ASSISTANT))
                .thenReturn(ConversationEntry.EntryType.ASSISTANT);
        when(chatMapper.mapConversationStatus(EntryStatusDTO.COMPLETED))
                .thenReturn(ConversationEntry.EntryStatus.COMPLETED);
        when(dao.findByChatAndIdempotencyKey(chat, "entry")).thenReturn(Optional.of(existing));

        service.createOrUpdate(chat, dto);

        verify(msgDao, never()).create(any(Message.class));
        verify(dao, never()).update(existing);
    }

    @Test
    void createOrUpdateDoesNotCreateMessageWhenTerminalEntryTextIsBlank() {
        var chat = new Chat();
        chat.setId("chat-id");

        var dto = new CreateOrUpdateConversationEntryDTO();
        dto.setIdempotencyKey("blank");
        dto.setType(ConversationTypeDTO.HUMAN);
        dto.setStatus(EntryStatusDTO.COMPLETED);
        dto.setText("   ");

        when(chatMapper.mapConversationType(ConversationTypeDTO.HUMAN))
                .thenReturn(ConversationEntry.EntryType.HUMAN);
        when(chatMapper.mapConversationStatus(EntryStatusDTO.COMPLETED))
                .thenReturn(ConversationEntry.EntryStatus.COMPLETED);
        when(dao.findByChatAndIdempotencyKey(chat, "blank")).thenReturn(Optional.empty());
        when(dao.findMaxSequenceByChat(chat)).thenReturn(0L);

        service.createOrUpdate(chat, dto);

        verify(msgDao, never()).create(any(Message.class));
    }

    @Test
    void createOrUpdateDoesNotCreateMessageWhenTerminalEntryTextIsNull() {
        var chat = new Chat();
        chat.setId("chat-id");

        var dto = new CreateOrUpdateConversationEntryDTO();
        dto.setIdempotencyKey("null-text");
        dto.setType(ConversationTypeDTO.HUMAN);
        dto.setStatus(EntryStatusDTO.INTERRUPTED);

        when(chatMapper.mapConversationType(ConversationTypeDTO.HUMAN))
                .thenReturn(ConversationEntry.EntryType.HUMAN);
        when(chatMapper.mapConversationStatus(EntryStatusDTO.INTERRUPTED))
                .thenReturn(ConversationEntry.EntryStatus.INTERRUPTED);
        when(dao.findByChatAndIdempotencyKey(chat, "null-text")).thenReturn(Optional.empty());
        when(dao.findMaxSequenceByChat(chat)).thenReturn(0L);

        service.createOrUpdate(chat, dto);

        verify(msgDao, never()).create(any(Message.class));
    }

    @Test
    void createOrUpdateDoesNotCreateMessageWhileEntryIsInProgress() {
        var chat = new Chat();
        chat.setId("chat-id");

        var dto = new CreateOrUpdateConversationEntryDTO();
        dto.setIdempotencyKey("in-progress");
        dto.setType(ConversationTypeDTO.ASSISTANT);
        dto.setStatus(EntryStatusDTO.IN_PROGRESS);
        dto.setText("Hello");

        when(chatMapper.mapConversationType(ConversationTypeDTO.ASSISTANT))
                .thenReturn(ConversationEntry.EntryType.ASSISTANT);
        when(chatMapper.mapConversationStatus(EntryStatusDTO.IN_PROGRESS))
                .thenReturn(ConversationEntry.EntryStatus.IN_PROGRESS);
        when(dao.findByChatAndIdempotencyKey(chat, "in-progress")).thenReturn(Optional.empty());
        when(dao.findMaxSequenceByChat(chat)).thenReturn(0L);

        service.createOrUpdate(chat, dto);

        verify(msgDao, never()).create(any(Message.class));
    }

    @Test
    void assistantResponseRetryForSameRequestDoesNotCreateAnotherEntry() {
        var chat = new Chat();
        chat.setId("chat-id");
        var existing = new ConversationEntry();
        existing.setType(ConversationEntry.EntryType.TEXT);
        existing.setMessageType(Message.MessageType.ASSISTANT);
        existing.setStatus(ConversationEntry.EntryStatus.COMPLETED);
        existing.setText("Answer");

        when(dao.findByChatAndIdempotencyKey(chat, "ai-response:request-1")).thenReturn(Optional.of(existing));

        assertSame(existing, service.createAssistantResponse(chat, "request-1", "Answer"));

        verify(dao, never()).create(any(ConversationEntry.class));
        verify(msgDao, never()).create(any(Message.class));
    }

    @Test
    void assistantResponseIsStoredAsCompletedTextEntryWithStableKey() {
        var chat = new Chat();
        chat.setId("chat-id");
        when(dao.findByChatAndIdempotencyKey(chat, "ai-response:request-1")).thenReturn(Optional.empty());
        when(dao.findMaxSequenceByChat(chat)).thenReturn(0L);

        var result = service.createAiResponse(chat, "request-1", Message.MessageType.ASSISTANT, "Answer", null);

        assertThat(result.getType()).isEqualTo(ConversationEntry.EntryType.TEXT);
        assertThat(result.getMessageType()).isEqualTo(Message.MessageType.ASSISTANT);
        assertThat(result.getStatus()).isEqualTo(ConversationEntry.EntryStatus.COMPLETED);
        assertThat(result.getIdempotencyKey()).isEqualTo("ai-response:request-1");
    }

    @Test
    void typedMessageEntryIsCompletedAndRetainsUserId() {
        var chat = new Chat();
        chat.setId("chat-id");
        var message = new Message();
        message.setType(Message.MessageType.HUMAN);
        message.setText("Hello");
        message.setUserId("user-1");
        when(dao.findMaxSequenceByChat(chat)).thenReturn(2L);

        service.createTypedMessage(chat, message);

        var captor = ArgumentCaptor.forClass(ConversationEntry.class);
        verify(dao).create(captor.capture());
        var entry = captor.getValue();
        assertThat(entry.getSequence()).isEqualTo(3L);
        assertThat(entry.getType()).isEqualTo(ConversationEntry.EntryType.TEXT);
        assertThat(entry.getMessageType()).isEqualTo(Message.MessageType.HUMAN);
        assertThat(entry.getStatus()).isEqualTo(ConversationEntry.EntryStatus.COMPLETED);
        assertThat(entry.getText()).isEqualTo("Hello");
        assertThat(entry.getUserId()).isEqualTo("user-1");
        verify(msgDao, never()).create(any(Message.class));
    }

    @Test
    void currentMessagesSuppressesLegacyDuplicatesAndKeepsSystemMessages() {
        var chat = new Chat();
        chat.setId("chat-id");
        var entry = new ConversationEntry();
        entry.setText("typed text");
        entry.setId("entry-id");
        entry.setType(ConversationEntry.EntryType.TEXT);
        entry.setMessageType(Message.MessageType.HUMAN);
        entry.setStatus(ConversationEntry.EntryStatus.COMPLETED);
        var entryMessage = new Message();
        entryMessage.setId("entry-id");
        entryMessage.setType(Message.MessageType.HUMAN);
        entryMessage.setText("typed text");
        entryMessage.setUserId("user-1");

        var voiceEntry = new ConversationEntry();
        voiceEntry.setType(ConversationEntry.EntryType.HUMAN);
        voiceEntry.setStatus(ConversationEntry.EntryStatus.COMPLETED);
        voiceEntry.setText("voice text");
        voiceEntry.setLegacyMessageId("voice-message-id");
        var voiceMessage = new Message();
        voiceMessage.setId("voice-message-id");
        voiceMessage.setType(Message.MessageType.HUMAN);
        voiceMessage.setText("voice text");

        var legacyDuplicate = new Message();
        legacyDuplicate.setId("entry-id");
        legacyDuplicate.setType(Message.MessageType.HUMAN);
        legacyDuplicate.setText("typed text");
        legacyDuplicate.setUserId("user-1");
        var systemMessage = new Message();
        systemMessage.setId("system-id");
        systemMessage.setType(Message.MessageType.SYSTEM);
        var legacyMessage = new Message();
        legacyMessage.setId("old-id");
        legacyMessage.setType(Message.MessageType.HUMAN);
        legacyMessage.setText("typed text");
        legacyMessage.setUserId("user-1");

        when(dao.findReplayEntries(chat)).thenReturn(java.util.List.of(entry, voiceEntry));
        when(chatMapper.mapEntryAsMessage(entry)).thenReturn(entryMessage);
        when(msgDao.findMessagesByChat(chat))
                .thenReturn(java.util.List.of(legacyDuplicate, voiceMessage, systemMessage, legacyMessage));

        var messages = service.currentMessages(chat);

        assertThat(messages).containsExactlyInAnyOrder(entryMessage, voiceMessage, systemMessage, legacyMessage);
        verify(chatMapper, never()).mapEntryAsMessage(voiceEntry);
    }

    @Test
    void currentMessagesOrdersRetainedLegacyMessagesByCreationDate() {
        var chat = new Chat();
        chat.setId("chat-id");

        var typedEntry = new ConversationEntry();
        typedEntry.setId("typed-entry");
        typedEntry.setType(ConversationEntry.EntryType.TEXT);
        typedEntry.setMessageType(Message.MessageType.HUMAN);
        typedEntry.setStatus(ConversationEntry.EntryStatus.COMPLETED);
        typedEntry.setSequence(2L);

        var typedMessage = new Message();
        typedMessage.setId("typed-entry");
        typedMessage.setType(Message.MessageType.HUMAN);
        typedMessage.setCreationDate(LocalDateTime.parse("2026-11-12T12:00:00"));

        var legacySystemMessage = new Message();
        legacySystemMessage.setId("legacy-system");
        legacySystemMessage.setType(Message.MessageType.SYSTEM);
        legacySystemMessage.setCreationDate(LocalDateTime.parse("2026-11-12T11:00:00"));

        when(dao.findReplayEntries(chat)).thenReturn(java.util.List.of(typedEntry));
        when(chatMapper.mapEntryAsMessage(typedEntry)).thenReturn(typedMessage);
        when(msgDao.findMessagesByChat(chat)).thenReturn(java.util.List.of(legacySystemMessage));

        assertThat(service.currentMessages(chat)).containsExactly(legacySystemMessage, typedMessage);
    }

    @Test
    void typedMessageRequiresRole() {
        assertThrows(IllegalArgumentException.class, () -> service.createTypedMessage(new Chat(), new Message()));
        verify(dao, never()).create(any(ConversationEntry.class));
    }

    @ParameterizedTest
    @EnumSource(Message.MessageType.class)
    void aiResponseAcceptsOnlyHumanAndAssistantRoles(Message.MessageType role) {
        var chat = new Chat();
        if (role == Message.MessageType.SYSTEM) {
            assertThrows(IllegalArgumentException.class,
                    () -> service.createAiResponse(chat, "request", role, "Reply", "user"));
            verify(dao, never()).create(any(ConversationEntry.class));
        } else {
            var entry = service.createAiResponse(chat, "request", role, "Reply", "user");
            assertThat(entry.getMessageType()).isEqualTo(role);
            assertThat(entry.getUserId()).isEqualTo("user");
            assertThat(entry.getText()).isEqualTo("Reply");
        }
    }

    @ParameterizedTest
    @CsvSource({
            "HUMAN,ASSISTANT,COMPLETED,Answer,user",
            "TEXT,HUMAN,COMPLETED,Answer,user",
            "TEXT,ASSISTANT,IN_PROGRESS,Answer,user",
            "TEXT,ASSISTANT,COMPLETED,Changed,user",
            "TEXT,ASSISTANT,COMPLETED,Answer,other"
    })
    void aiResponseRejectsConflictingRetries(ConversationEntry.EntryType type, Message.MessageType role,
            ConversationEntry.EntryStatus status, String text, String user) {
        var chat = new Chat();
        var existing = new ConversationEntry();
        existing.setType(type);
        existing.setMessageType(role);
        existing.setStatus(status);
        existing.setText(text);
        existing.setUserId(user);
        when(dao.findByChatAndIdempotencyKey(chat, "ai-response:request")).thenReturn(Optional.of(existing));

        assertThrows(IdempotencyConflictException.class,
                () -> service.createAiResponse(chat, "request", Message.MessageType.ASSISTANT, "Answer", "user"));
        verify(dao, never()).create(any(ConversationEntry.class));
    }

    @ParameterizedTest
    @MethodSource("voiceMessages")
    void currentMessagesHidesOnlyEmptyVoiceEntries(ConversationEntry.EntryType type, String text, boolean visible) {
        var chat = new Chat();
        var entry = new ConversationEntry();
        entry.setType(type);
        entry.setText(text);
        var message = new Message();
        message.setText(text);
        when(dao.findReplayEntries(chat)).thenReturn(List.of(entry));
        when(chatMapper.mapEntryAsMessage(entry)).thenReturn(message);

        assertThat(service.currentMessages(chat)).isEqualTo(visible ? List.of(message) : List.of());
    }

    static Stream<Arguments> voiceMessages() {
        return Stream.of(
                Arguments.of(ConversationEntry.EntryType.HUMAN, null, false),
                Arguments.of(ConversationEntry.EntryType.HUMAN, " ", false),
                Arguments.of(ConversationEntry.EntryType.HUMAN, "Question", true),
                Arguments.of(ConversationEntry.EntryType.ASSISTANT, null, false),
                Arguments.of(ConversationEntry.EntryType.ASSISTANT, " ", false),
                Arguments.of(ConversationEntry.EntryType.ASSISTANT, "Answer", true),
                Arguments.of(ConversationEntry.EntryType.TEXT, "", true));
    }

    @Test
    void currentMessagesMapsEntriesWhoseLegacyRowIsMissing() {
        var chat = new Chat();
        var entry = new ConversationEntry();
        entry.setType(ConversationEntry.EntryType.TEXT);
        entry.setId("entry");
        entry.setLegacyMessageId("missing");
        var message = new Message();
        message.setId("entry");
        when(dao.findReplayEntries(chat)).thenReturn(List.of(entry));
        when(chatMapper.mapEntryAsMessage(entry)).thenReturn(message);

        assertThat(service.currentMessages(chat)).containsExactly(message);
    }

    @ParameterizedTest
    @CsvSource({
            "missing,false",
            "no-chat,false",
            "other-chat,false",
            "in-progress,false",
            "completed,true",
            "interrupted,true"
    })
    void findMessageByIdOnlyReturnsTerminalEntriesInTheRequestedChat(String scenario, boolean visible) {
        var chat = new Chat();
        chat.setId("chat");
        var entry = new ConversationEntry();
        entry.setChat(chat);
        entry.setStatus(ConversationEntry.EntryStatus.COMPLETED);
        switch (scenario) {
            case "missing" -> entry = null;
            case "no-chat" -> entry.setChat(null);
            case "other-chat" -> {
                var other = new Chat();
                other.setId("other");
                entry.setChat(other);
            }
            case "in-progress" -> entry.setStatus(ConversationEntry.EntryStatus.IN_PROGRESS);
            case "interrupted" -> entry.setStatus(ConversationEntry.EntryStatus.INTERRUPTED);
            default -> {
                // The completed entry belongs to the requested chat.
            }
        }
        var message = new Message();
        message.setId("message");
        when(dao.findById("entry")).thenReturn(entry);
        if (visible) {
            when(chatMapper.mapEntryAsMessage(entry)).thenReturn(message);
        }

        assertThat(service.findMessageById(chat, "entry")).isSameAs(visible ? message : null);
    }

    @ParameterizedTest
    @CsvSource(value = {
            "NULL,user,true,true",
            "user,NULL,true,false",
            "user,user,false,false",
            "NULL,user,false,true",
            "user,other,false,true"
    }, nullValues = "NULL")
    void entryUserMetadataIsImmutableButCanBeInitiallyAssigned(String storedUser, String requestedUser,
            boolean growingText, boolean changes) {
        var chat = new Chat();
        var dto = assistantEntryRequest("entry", null);
        dto.setUserId(requestedUser);
        var entry = assistantEntry("entry", null);
        entry.setUserId(storedUser);
        if (growingText) {
            entry.setText("Hel");
        }
        mockAssistantMappings();
        when(dao.findByChatAndIdempotencyKey(chat, "entry")).thenReturn(Optional.of(entry));
        when(dao.update(entry)).thenReturn(entry);

        if (storedUser != null && requestedUser != null && changes) {
            assertThrows(IdempotencyConflictException.class, () -> service.createOrUpdate(chat, dto));
        } else {
            var result = service.createOrUpdate(chat, dto);
            assertThat(result.getUserId()).isEqualTo(storedUser == null ? requestedUser : storedUser);
            assertThat(result.getText()).isEqualTo("Hello");
        }
    }

    @Test
    void entryCanAcquireUserAndAgentMetadataWithoutChangingText() {
        var chat = new Chat();
        var dto = assistantEntryRequest("entry", "config");
        dto.setUserId("user");
        var entry = assistantEntry("entry", null);
        mockAssistantMappings();
        when(dao.findByChatAndIdempotencyKey(chat, "entry")).thenReturn(Optional.of(entry));
        when(dao.update(entry)).thenReturn(entry);

        var result = service.createOrUpdate(chat, dto);

        assertThat(result.getUserId()).isEqualTo("user");
        assertThat(result.getAgentConfigVersion()).isEqualTo("config");
        assertThat(result.getText()).isEqualTo("Hello");
    }

    @ParameterizedTest
    @CsvSource({ "SYSTEM,false", "HUMAN,true" })
    void systemEntriesAdvanceLastMessageDateEvenWithoutText(ConversationEntry.EntryType type, boolean typed) {
        var chat = new Chat();
        chat.setId("chat");
        var created = new ConversationEntry();
        created.setType(typed ? ConversationEntry.EntryType.TEXT : type);
        created.setStatus(ConversationEntry.EntryStatus.COMPLETED);
        created.setMessageType(Message.MessageType.SYSTEM);
        created.setCreationDate(LocalDateTime.parse("2026-11-12T10:00:00"));
        when(dao.create(any(ConversationEntry.class))).thenReturn(created);
        if (typed) {
            var message = new Message();
            message.setType(Message.MessageType.SYSTEM);
            service.createTypedMessage(chat, message);
        } else {
            var dto = assistantEntryRequest("entry", null);
            dto.setStatus(EntryStatusDTO.COMPLETED);
            when(chatMapper.mapConversationStatus(EntryStatusDTO.COMPLETED))
                    .thenReturn(ConversationEntry.EntryStatus.COMPLETED);
            service.createOrUpdate(chat, dto);
        }

        assertThat(chat.getLastMessageDate()).isEqualTo(LocalDateTime.parse("2026-11-12T10:00:00"));
    }

    @Test
    void lastMessageDatePrefersModificationDateToCreationDate() {
        var chat = new Chat();
        var created = new ConversationEntry();
        created.setType(ConversationEntry.EntryType.TEXT);
        created.setMessageType(Message.MessageType.ASSISTANT);
        created.setText("Answer");
        created.setCreationDate(LocalDateTime.parse("2026-11-12T10:00:00"));
        created.setModificationDate(LocalDateTime.parse("2026-11-12T11:00:00"));
        when(dao.create(any(ConversationEntry.class))).thenReturn(created);

        service.createAssistantResponse(chat, "request", "Answer");

        assertThat(chat.getLastMessageDate()).isEqualTo(LocalDateTime.parse("2026-11-12T11:00:00"));
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
