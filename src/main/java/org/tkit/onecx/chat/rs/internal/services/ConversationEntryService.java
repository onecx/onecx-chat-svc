package org.tkit.onecx.chat.rs.internal.services;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.tkit.onecx.chat.domain.daos.ChatDAO;
import org.tkit.onecx.chat.domain.daos.ConversationEntryDAO;
import org.tkit.onecx.chat.domain.daos.MessageDAO;
import org.tkit.onecx.chat.domain.models.Chat;
import org.tkit.onecx.chat.domain.models.ConversationEntry;
import org.tkit.onecx.chat.domain.models.Message;
import org.tkit.onecx.chat.rs.internal.mappers.ChatMapper;
import org.tkit.quarkus.jpa.exceptions.ConstraintException;

import gen.org.tkit.onecx.chat.rs.internal.model.CreateOrUpdateConversationEntryDTO;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ApplicationScoped
@Transactional(Transactional.TxType.NOT_SUPPORTED)
public class ConversationEntryService {

    @Inject
    ConversationEntryDAO dao;

    @Inject
    ChatDAO chatDAO;

    @Inject
    MessageDAO messageDAO;

    @Inject
    ChatMapper chatMapper;

    /**
     * Creates a new conversation entry or updates an existing one
     * identified by the idempotency key.
     * <p>
     * Story requirements:
     * - first write allocates a stable sequence number
     * - repeated identical requests are treated as no-ops
     * - incompatible retries result in conflict
     * - terminal entries cannot be modified
     */
    @Transactional
    public ConversationEntry createOrUpdate(Chat chat, CreateOrUpdateConversationEntryDTO entry) {
        chatDAO.lock(chat.getId());
        final String idempotencyKey = entry.getIdempotencyKey();
        ConversationEntry.EntryType actualType = chatMapper.mapConversationType(entry.getType());
        ConversationEntry.EntryStatus actualStatus = chatMapper.mapConversationStatus(entry.getStatus());
        final String newText = entry.getText();
        final String agentConfigVersion = entry.getAgentConfigVersion();
        final String userId = entry.getUserId();
        Optional<ConversationEntry> existing = dao.findByChatAndIdempotencyKey(chat, idempotencyKey);

        if (existing.isPresent()) {
            return update(existing.get(), actualType, actualStatus, newText, agentConfigVersion, userId);
        }

        try {
            var created = create(chat, idempotencyKey, actualType, actualStatus, newText, agentConfigVersion, userId,
                    null);
            if (isTerminal(created.getStatus())) {
                updateLastMessageDate(chat, created);
            }
            return created;
        } catch (ConstraintException ex) {
            // Handle potential race condition where another request created
            log.debug("Persistence conflict creating conversation entry for chat {} idempotencyKey {}, retrying",
                    chat.getId(), entry.getIdempotencyKey(), ex);
            return dao.findByChatAndIdempotencyKey(chat, idempotencyKey)
                    .map(e -> update(e, actualType, actualStatus, newText, agentConfigVersion, userId))
                    .orElseThrow(() -> ex);
        }
    }

    /**
     * Returns entries visible during replay.
     * <p>
     * DAO already filters out IN_PROGRESS entries and
     * returns data ordered by sequence.
     */
    public List<ConversationEntry> replay(Chat chat) {
        return dao.findReplayEntries(chat);
    }

    @Transactional
    public void deleteByChat(Chat chat) {
        dao.deleteByChat(chat);
    }

    @Transactional
    public ConversationEntry createTypedMessage(Chat chat, Message message) {
        if (message.getType() == null) {
            throw new IllegalArgumentException("A message type is required for conversation entries.");
        }
        chatDAO.lock(chat.getId());
        var entry = create(chat, "typed-message:" + UUID.randomUUID(), ConversationEntry.EntryType.TEXT,
                ConversationEntry.EntryStatus.COMPLETED, message.getText(), null, message.getUserId(),
                message.getType());
        updateLastMessageDate(chat, entry);
        return entry;
    }

    @Transactional
    public ConversationEntry createAssistantResponse(Chat chat, String requestMessageId, String text) {
        return createAiResponse(chat, requestMessageId, Message.MessageType.ASSISTANT, text, null);
    }

    @Transactional
    public ConversationEntry createAiResponse(Chat chat, String requestMessageId, Message.MessageType messageType,
            String text, String userId) {
        if (messageType != Message.MessageType.HUMAN && messageType != Message.MessageType.ASSISTANT) {
            throw new IllegalArgumentException("AI response entries must be HUMAN or ASSISTANT.");
        }
        chatDAO.lock(chat.getId());
        final String idempotencyKey = "ai-response:" + requestMessageId;
        var existing = dao.findByChatAndIdempotencyKey(chat, idempotencyKey);
        if (existing.isPresent()) {
            var entry = existing.get();
            if (entry.getType() != ConversationEntry.EntryType.TEXT
                    || entry.getMessageType() != messageType
                    || entry.getStatus() != ConversationEntry.EntryStatus.COMPLETED
                    || !Objects.equals(entry.getText(), text)
                    || !Objects.equals(entry.getUserId(), userId)) {
                throw new IdempotencyConflictException("AI response retry does not match the stored response.");
            }
            return entry;
        }
        var entry = create(chat, idempotencyKey, ConversationEntry.EntryType.TEXT,
                ConversationEntry.EntryStatus.COMPLETED, text, null, userId, messageType);
        updateLastMessageDate(chat, entry);
        return entry;
    }

    public boolean hasAiResponse(Chat chat, String requestMessageId) {
        return dao.findByChatAndIdempotencyKey(chat, "ai-response:" + requestMessageId).isPresent();
    }

    public List<Message> currentMessages(Chat chat) {
        var entries = dao.findReplayEntries(chat);
        var legacyMessages = messageDAO.findMessagesByChat(chat);
        var orderedMessages = new ArrayList<OrderedMessage>();
        Set<String> representedMessageIds = new HashSet<>();
        var legacyMessagesById = new HashMap<String, Message>();
        for (var legacy : legacyMessages) {
            legacyMessagesById.put(legacy.getId(), legacy);
        }
        for (var entry : entries) {
            if ((entry.getType() == ConversationEntry.EntryType.HUMAN
                    || entry.getType() == ConversationEntry.EntryType.ASSISTANT)
                    && (entry.getText() == null || entry.getText().isBlank())) {
                continue;
            }
            if (entry.getLegacyMessageId() != null
                    && legacyMessagesById.containsKey(entry.getLegacyMessageId())) {
                var legacy = legacyMessagesById.get(entry.getLegacyMessageId());
                orderedMessages.add(new OrderedMessage(legacy, entry.getSequence()));
                representedMessageIds.add(entry.getLegacyMessageId());
                continue;
            }
            var message = chatMapper.mapEntryAsMessage(entry);
            orderedMessages.add(new OrderedMessage(message, entry.getSequence()));
            if (entry.getType() == ConversationEntry.EntryType.TEXT) {
                representedMessageIds.add(entry.getId());
            }
            if (entry.getLegacyMessageId() != null) {
                representedMessageIds.add(entry.getLegacyMessageId());
            }
        }

        for (var legacy : legacyMessages) {
            if (!representedMessageIds.contains(legacy.getId())) {
                orderedMessages.add(new OrderedMessage(legacy, null));
            }
        }
        orderedMessages.sort(Comparator
                .comparing(OrderedMessage::sequence, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(message -> message.message().getCreationDate(),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(message -> message.message().getId(),
                        Comparator.nullsLast(Comparator.naturalOrder())));
        return orderedMessages.stream().map(OrderedMessage::message).toList();
    }

    public Message findMessageById(Chat chat, String id) {
        var entry = dao.findById(id);
        if (entry == null || entry.getChat() == null || !Objects.equals(entry.getChat().getId(), chat.getId())
                || !isTerminal(entry.getStatus())) {
            return null;
        }
        return chatMapper.mapEntryAsMessage(entry);
    }

    /**
     * Creates a brand new entry.
     * The sequence number is assigned only once and
     * must never change afterwards.
     */
    private ConversationEntry create(Chat chat, String idempotencyKey, ConversationEntry.EntryType type,
            ConversationEntry.EntryStatus status, String text, String agentConfigVersion, String userId,
            Message.MessageType messageType) {

        ConversationEntry entry = new ConversationEntry();
        entry.setChat(chat);
        entry.setIdempotencyKey(idempotencyKey);
        entry.setType(type);
        entry.setStatus(status);
        entry.setText(text);
        entry.setAgentConfigVersion(agentConfigVersion);
        entry.setUserId(userId);
        entry.setMessageType(messageType);

        Long nextSequence = dao.findMaxSequenceByChat(chat) + 1;

        entry.setSequence(nextSequence);
        var created = dao.create(entry);
        return created == null ? entry : created;
    }

    /**
     * Updates an existing entry.
     * <p>
     * Rules:
     * - terminal entries cannot be updated
     * - identical retries are ignored
     * - checkpoint text must be cumulative
     * - status transitions must be valid
     */
    private ConversationEntry update(ConversationEntry entry, ConversationEntry.EntryType newType,
            ConversationEntry.EntryStatus newStatus, String newText, String agentConfigVersion, String userId) {

        validateImmutableMetadata(entry, newType, agentConfigVersion, userId);
        if (isNoOps(entry, newStatus, newText, agentConfigVersion, userId)) {
            return entry;
        }
        validateTerminalState(entry);
        validateMonotonicCheckpoint(entry.getText(), newText);

        entry.setText(newText);
        entry.setStatus(newStatus);
        if (entry.getAgentConfigVersion() == null) {
            entry.setAgentConfigVersion(agentConfigVersion);
        }
        if (entry.getUserId() == null) {
            entry.setUserId(userId);
        }

        final ConversationEntry updated = dao.update(entry);
        if (isTerminal(updated.getStatus())) {
            updateLastMessageDate(updated.getChat(), updated);
        }

        return updated;
    }

    private void updateLastMessageDate(Chat chat, ConversationEntry entry) {
        boolean systemMessage = entry.getType() == ConversationEntry.EntryType.SYSTEM
                || entry.getMessageType() == Message.MessageType.SYSTEM;
        if (chat == null || (!systemMessage && (entry.getText() == null || entry.getText().isBlank()))) {
            return;
        }
        var date = entry.getModificationDate() != null ? entry.getModificationDate()
                : entry.getCreationDate() != null ? entry.getCreationDate() : java.time.LocalDateTime.now();
        chatDAO.updateLastMessageDate(chat.getId(), date);
        chat.setLastMessageDate(date);
    }

    private boolean isTerminal(ConversationEntry.EntryStatus status) {
        return status == ConversationEntry.EntryStatus.COMPLETED
                || status == ConversationEntry.EntryStatus.INTERRUPTED;
    }

    /**
     * Detects idempotent retries.
     * <p>
     * If nothing changes the request is treated as a no-ops.
     */
    private boolean isNoOps(ConversationEntry entry, ConversationEntry.EntryStatus status, String text,
            String agentConfigVersion, String userId) {
        return Objects.equals(entry.getStatus(), status) && Objects.equals(entry.getText(), text)
                && (agentConfigVersion == null || Objects.equals(entry.getAgentConfigVersion(), agentConfigVersion))
                && (userId == null || Objects.equals(entry.getUserId(), userId));
    }

    private void validateImmutableMetadata(ConversationEntry entry, ConversationEntry.EntryType type,
            String agentConfigVersion, String userId) {
        if (entry.getType() != type) {
            throw new IdempotencyConflictException("Conversation entry type must not change.");
        }
        if (entry.getAgentConfigVersion() != null && agentConfigVersion != null
                && !Objects.equals(entry.getAgentConfigVersion(), agentConfigVersion)) {
            throw new IdempotencyConflictException("Agent configuration version must not change.");
        }
        if (entry.getUserId() != null && userId != null && !Objects.equals(entry.getUserId(), userId)) {
            throw new IdempotencyConflictException("Conversation entry user must not change.");
        }
    }

    /**
     * Validates that checkpoint text only grows.
     * <p>
     * Valid:
     * "Hel"
     * "Hello"
     * "Hello World"
     * <p>
     * Invalid:
     * "Hello World"
     * "Hello"
     */
    private void validateMonotonicCheckpoint(String currentText, String newText) {

        if (currentText == null || currentText.isBlank()) {
            return;
        }

        if (newText == null || !newText.startsWith(currentText)) {
            throw new IdempotencyConflictException(
                    "Checkpoint text must be cumulative and monotonic.");
        }
    }

    /**
     * Protects terminal entries from modification.
     * <p>
     * COMPLETED and INTERRUPTED are immutable.
     */
    private void validateTerminalState(ConversationEntry entry) {

        if (entry.getStatus() == ConversationEntry.EntryStatus.COMPLETED
                || entry.getStatus() == ConversationEntry.EntryStatus.INTERRUPTED) {

            throw new IllegalStateException(
                    "Conversation entry is already in terminal state.");
        }
    }

    private record OrderedMessage(Message message, Long sequence) {
    }
}
