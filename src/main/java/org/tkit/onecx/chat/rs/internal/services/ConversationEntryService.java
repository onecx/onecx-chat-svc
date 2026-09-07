package org.tkit.onecx.chat.rs.internal.services;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.tkit.onecx.chat.domain.daos.ConversationEntryDAO;
import org.tkit.onecx.chat.domain.models.Chat;
import org.tkit.onecx.chat.domain.models.ConversationEntry;
import org.tkit.quarkus.jpa.exceptions.ConstraintException;

import gen.org.tkit.onecx.chat.rs.internal.model.CreateOrUpdateConversationEntryDTO;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ApplicationScoped
@Transactional(Transactional.TxType.NOT_SUPPORTED)
public class ConversationEntryService {

    @Inject
    ConversationEntryDAO dao;

    @Transactional
    public ConversationEntryOutcome createOrUpdate(Chat chat, CreateOrUpdateConversationEntryDTO dto) {
        var existing = dao.findByChatIdAndIdempotencyKey(chat.getId(), dto.getIdempotencyKey());

        if (existing.isEmpty()) {
            try {
                return createNewEntry(chat, dto);
            } catch (ConstraintException ex) {
                // Concurrent retried/duplicate delivery may race on the unique constraint for
                // (chatId, idempotencyKey) or the per-chat sequence number. Re-read the row
                // that the winning transaction persisted and reconcile against it instead of
                // surfacing an unhandled 500.
                log.debug("Persistence conflict creating conversation entry for chat {} idempotencyKey {}, retrying",
                        chat.getId(), dto.getIdempotencyKey(), ex);
                var reloaded = dao.findByChatIdAndIdempotencyKey(chat.getId(), dto.getIdempotencyKey());
                if (reloaded.isPresent()) {
                    return reconcile(reloaded.get(), dto);
                }
                // Sequence number collision with a different idempotency key: retry once.
                try {
                    return createNewEntry(chat, dto);
                } catch (ConstraintException retryEx) {
                    var reloadedAfterRetry = dao.findByChatIdAndIdempotencyKey(chat.getId(), dto.getIdempotencyKey());
                    if (reloadedAfterRetry.isPresent()) {
                        return reconcile(reloadedAfterRetry.get(), dto);
                    }
                    throw retryEx;
                }
            }
        }

        return reconcile(existing.get(), dto);
    }

    private ConversationEntryOutcome createNewEntry(Chat chat, CreateOrUpdateConversationEntryDTO dto) {
        var entry = new ConversationEntry();
        entry.setChat(chat);
        entry.setIdempotencyKey(dto.getIdempotencyKey());
        entry.setEntryType(ConversationEntry.EntryType.valueOf(dto.getEntryType().name()));
        entry.setText(dto.getText());
        entry.setUserId(dto.getUserId());
        entry.setStatus(dto.getStatus() != null ? ConversationEntry.Status.valueOf(dto.getStatus().name())
                : ConversationEntry.Status.IN_PROGRESS);
        entry.setSequenceNumber(dao.findMaxSequenceNumberForChat(chat.getId()) + 1);

        dao.create(entry);
        return new ConversationEntryOutcome(entry, true);
    }

    private ConversationEntryOutcome reconcile(ConversationEntry current, CreateOrUpdateConversationEntryDTO dto) {
        if (current.getStatus() != ConversationEntry.Status.IN_PROGRESS) {
            throw new ConversationEntryConflictException(
                    "Entry is already finalized with terminal status " + current.getStatus());
        }

        var existingText = current.getText() == null ? "" : current.getText();
        // A null text in the request means "no text change requested" (e.g. a caller
        // finalizing the status without resending the cumulative text), not "clear the text".
        var requestedText = dto.getText() == null ? existingText : dto.getText();
        var requestedStatus = dto.getStatus() != null ? ConversationEntry.Status.valueOf(dto.getStatus().name())
                : ConversationEntry.Status.IN_PROGRESS;

        if (requestedText.equals(existingText) && requestedStatus == current.getStatus()) {
            return new ConversationEntryOutcome(current, false);
        }

        if (requestedText.startsWith(existingText)) {
            current.setText(requestedText);
            if (dto.getStatus() != null) {
                current.setStatus(ConversationEntry.Status.valueOf(dto.getStatus().name()));
            }
            try {
                dao.update(current);
            } catch (ConstraintException ex) {
                log.debug("Persistence conflict updating conversation entry {}, reconciling with latest state",
                        current.getId(), ex);
                var reloaded = dao.findByChatIdAndIdempotencyKey(current.getChat().getId(), current.getIdempotencyKey());
                if (reloaded.isPresent()) {
                    return reconcile(reloaded.get(), dto);
                }
                throw ex;
            }
            return new ConversationEntryOutcome(current, false);
        }

        throw new ConversationEntryConflictException(
                "Incompatible retry: requested text does not extend the stored cumulative text");
    }

    public List<ConversationEntry> replay(Chat chat) {
        return dao.findVisibleTerminalEntriesForChat(chat.getId());
    }

    public static class ConversationEntryConflictException extends RuntimeException {
        public ConversationEntryConflictException(String message) {
            super(message);
        }
    }

    @Getter
    @AllArgsConstructor
    public static class ConversationEntryOutcome {
        private final ConversationEntry entry;
        private final boolean created;
    }
}
