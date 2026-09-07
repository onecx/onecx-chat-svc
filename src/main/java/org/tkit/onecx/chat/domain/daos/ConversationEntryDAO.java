package org.tkit.onecx.chat.domain.daos;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.criteria.Predicate;
import jakarta.transaction.Transactional;

import org.tkit.onecx.chat.domain.models.ConversationEntry;
import org.tkit.onecx.chat.domain.models.ConversationEntry_;
import org.tkit.quarkus.jpa.daos.AbstractDAO;
import org.tkit.quarkus.jpa.exceptions.DAOException;
import org.tkit.quarkus.jpa.models.TraceableEntity_;

@ApplicationScoped
@Transactional(Transactional.TxType.SUPPORTS)
public class ConversationEntryDAO extends AbstractDAO<ConversationEntry> {

    public Optional<ConversationEntry> findByChatIdAndIdempotencyKey(String chatId, String idempotencyKey) {
        try {
            var cb = this.getEntityManager().getCriteriaBuilder();
            var cq = cb.createQuery(ConversationEntry.class);
            var root = cq.from(ConversationEntry.class);
            List<Predicate> predicates = new ArrayList<>();

            predicates.add(cb.equal(root.get(ConversationEntry_.CHAT).get(TraceableEntity_.ID), chatId));
            predicates.add(cb.equal(root.get(ConversationEntry_.IDEMPOTENCY_KEY), idempotencyKey));

            cq.where(cb.and(predicates.toArray(new Predicate[0])));

            var results = getEntityManager().createQuery(cq).getResultList();
            return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
        } catch (Exception ex) {
            throw new DAOException(ErrorKeys.ERROR_FIND_BY_CHAT_AND_IDEMPOTENCY_KEY, ex);
        }
    }

    public Long findMaxSequenceNumberForChat(String chatId) {
        try {
            var cb = this.getEntityManager().getCriteriaBuilder();
            var cq = cb.createQuery(Long.class);
            var root = cq.from(ConversationEntry.class);

            cq.select(cb.max(root.get(ConversationEntry_.SEQUENCE_NUMBER)));
            cq.where(cb.equal(root.get(ConversationEntry_.CHAT).get(TraceableEntity_.ID), chatId));

            var result = getEntityManager().createQuery(cq).getSingleResult();
            return result == null ? 0L : result;
        } catch (Exception ex) {
            throw new DAOException(ErrorKeys.ERROR_FIND_MAX_SEQUENCE_NUMBER, ex);
        }
    }

    public List<ConversationEntry> findVisibleTerminalEntriesForChat(String chatId) {
        try {
            var cb = this.getEntityManager().getCriteriaBuilder();
            var cq = cb.createQuery(ConversationEntry.class);
            var root = cq.from(ConversationEntry.class);
            List<Predicate> predicates = new ArrayList<>();

            predicates.add(cb.equal(root.get(ConversationEntry_.CHAT).get(TraceableEntity_.ID), chatId));
            predicates.add(root.get(ConversationEntry_.STATUS)
                    .in(List.of(ConversationEntry.Status.COMPLETED, ConversationEntry.Status.INTERRUPTED)));

            cq.where(cb.and(predicates.toArray(new Predicate[0])));
            cq.orderBy(cb.asc(root.get(ConversationEntry_.SEQUENCE_NUMBER)));

            return getEntityManager().createQuery(cq).getResultList();
        } catch (Exception ex) {
            throw new DAOException(ErrorKeys.ERROR_FIND_VISIBLE_TERMINAL_ENTRIES, ex);
        }
    }

    public enum ErrorKeys {
        ERROR_FIND_BY_CHAT_AND_IDEMPOTENCY_KEY,
        ERROR_FIND_MAX_SEQUENCE_NUMBER,
        ERROR_FIND_VISIBLE_TERMINAL_ENTRIES
    }
}
