package org.tkit.onecx.chat.domain.daos;

import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import org.tkit.onecx.chat.domain.models.Chat;
import org.tkit.onecx.chat.domain.models.ConversationEntry;
import org.tkit.onecx.chat.domain.models.ConversationEntry_;
import org.tkit.quarkus.jpa.daos.AbstractDAO;
import org.tkit.quarkus.jpa.exceptions.DAOException;

@ApplicationScoped
@Transactional(Transactional.TxType.SUPPORTS)
public class ConversationEntryDAO extends AbstractDAO<ConversationEntry> {

    public Optional<ConversationEntry> findByChatAndIdempotencyKey(
            Chat chat,
            String idempotencyKey) {

        try {
            var cb = this.getEntityManager().getCriteriaBuilder();
            var cq = cb.createQuery(ConversationEntry.class);
            var root = cq.from(ConversationEntry.class);

            cq.where(
                    cb.and(
                            cb.equal(root.get(ConversationEntry_.CHAT), chat),
                            cb.equal(root.get(ConversationEntry_.IDEMPOTENCY_KEY), idempotencyKey)));

            return this.getEntityManager()
                    .createQuery(cq)
                    .getResultStream()
                    .findFirst();

        } catch (Exception ex) {
            throw new DAOException(ErrorKeys.ERROR_FIND_CONVERSATION_ENTRY_BY_IDEMPOTENCY_KEY, ex);
        }
    }

    public Long findMaxSequenceByChat(Chat chat) {

        try {
            var cb = this.getEntityManager().getCriteriaBuilder();
            var cq = cb.createQuery(Long.class);
            var root = cq.from(ConversationEntry.class);

            cq.select(cb.max(root.get(ConversationEntry_.SEQUENCE)));
            cq.where(cb.equal(root.get(ConversationEntry_.CHAT), chat));

            return Optional.ofNullable(
                    this.getEntityManager()
                            .createQuery(cq)
                            .getSingleResult())
                    .orElse(0L);

        } catch (Exception ex) {
            throw new DAOException(ErrorKeys.ERROR_FIND_CONVERSATION_ENTRY_MAX_SEQUENCE, ex);
        }
    }

    public List<ConversationEntry> findReplayEntries(Chat chat) {

        try {
            var cb = this.getEntityManager().getCriteriaBuilder();
            var cq = cb.createQuery(ConversationEntry.class);
            var root = cq.from(ConversationEntry.class);

            cq.where(
                    cb.and(
                            cb.equal(root.get(ConversationEntry_.CHAT), chat),
                            cb.notEqual(
                                    root.get(ConversationEntry_.STATUS),
                                    ConversationEntry.EntryStatus.IN_PROGRESS)));

            cq.orderBy(
                    cb.asc(root.get(ConversationEntry_.SEQUENCE)));

            return this.getEntityManager()
                    .createQuery(cq)
                    .getResultList();

        } catch (Exception ex) {
            throw new DAOException(ErrorKeys.ERROR_FIND_CONVERSATION_ENTRY_REPLAY_ENTRIES, ex);
        }
    }

    public enum ErrorKeys {

        ERROR_FIND_CONVERSATION_ENTRY_BY_IDEMPOTENCY_KEY,
        ERROR_FIND_CONVERSATION_ENTRY_MAX_SEQUENCE,
        ERROR_FIND_CONVERSATION_ENTRY_REPLAY_ENTRIES;

    }
}