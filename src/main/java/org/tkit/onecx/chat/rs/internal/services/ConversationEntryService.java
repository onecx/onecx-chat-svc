package org.tkit.onecx.chat.rs.internal.services;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.tkit.onecx.chat.domain.daos.ChatDAO;
import org.tkit.onecx.chat.domain.daos.ConversationEntryDAO;
import org.tkit.onecx.chat.domain.models.Chat;
import org.tkit.onecx.chat.domain.models.ConversationEntry;
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
        ConversationEntry.EntryStatus actualStatus = chatMapper.mapConversationStatus(entry.getStatus());
        final String newText = entry.getText();
        Optional<ConversationEntry> existing = dao.findByChatAndIdempotencyKey(chat, idempotencyKey);

        if (existing.isPresent()) {
            return update(existing.get(), actualStatus, newText);
        }

        try {
            return create(chat, idempotencyKey, ConversationEntry.EntryType.HUMAN, actualStatus, newText);
        } catch (ConstraintException ex) {
            // Handle potential race condition where another request created
            log.debug("Persistence conflict creating conversation entry for chat {} idempotencyKey {}, retrying",
                    chat.getId(), entry.getIdempotencyKey(), ex);
            return dao.findByChatAndIdempotencyKey(chat, idempotencyKey)
                    .map(e -> update(e, actualStatus, newText))
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

    /**
     * Creates a brand new entry.
     * The sequence number is assigned only once and
     * must never change afterwards.
     */
    private ConversationEntry create(Chat chat, String idempotencyKey, ConversationEntry.EntryType type,
            ConversationEntry.EntryStatus status, String text) {

        ConversationEntry entry = new ConversationEntry();
        entry.setChat(chat);
        entry.setIdempotencyKey(idempotencyKey);
        entry.setType(type);
        entry.setStatus(status);
        entry.setText(text);

        Long nextSequence = dao.findMaxSequenceByChat(chat) + 1;

        entry.setSequence(nextSequence);
        dao.create(entry);

        return entry;
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
    private ConversationEntry update(ConversationEntry entry, ConversationEntry.EntryStatus newStatus, String newText) {

        if (isNoOps(entry, newStatus, newText)) {
            return entry;
        }
        validateTerminalState(entry);
        validateMonotonicCheckpoint(entry.getText(), newText);

        entry.setText(newText);
        entry.setStatus(newStatus);

        return dao.update(entry);
    }

    /**
     * Detects idempotent retries.
     * <p>
     * If nothing changes the request is treated as a no-ops.
     */
    private boolean isNoOps(ConversationEntry entry, ConversationEntry.EntryStatus status, String text) {
        return Objects.equals(entry.getStatus(), status) && Objects.equals(entry.getText(), text);
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
}
