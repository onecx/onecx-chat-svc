package org.tkit.onecx.chat.domain.daos;

import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import org.tkit.onecx.chat.domain.models.Chat;
import org.tkit.onecx.chat.domain.models.ToolCall;
import org.tkit.quarkus.jpa.daos.AbstractDAO;
import org.tkit.quarkus.jpa.exceptions.DAOException;

@ApplicationScoped
@Transactional(Transactional.TxType.SUPPORTS)
public class ToolCallDAO extends AbstractDAO<ToolCall> {

    public Optional<ToolCall> findByChatAndAttemptId(Chat chat, String attemptId) {
        try {
            return getEntityManager().createQuery(
                    "select t from ToolCall t where t.chat = :chat and t.attemptId = :attemptId", ToolCall.class)
                    .setParameter("chat", chat)
                    .setParameter("attemptId", attemptId)
                    .getResultStream()
                    .findFirst();
        } catch (Exception ex) {
            throw new DAOException(ErrorKeys.FIND_BY_CHAT_AND_ATTEMPT_ID, ex);
        }
    }

    public Optional<ToolCall> findByChatAndIdempotencyKey(Chat chat, String idempotencyKey) {
        try {
            return getEntityManager().createQuery(
                    "select t from ToolCall t where t.chat = :chat and t.idempotencyKey = :key", ToolCall.class)
                    .setParameter("chat", chat)
                    .setParameter("key", idempotencyKey)
                    .getResultStream()
                    .findFirst();
        } catch (Exception ex) {
            throw new DAOException(ErrorKeys.FIND_BY_CHAT_AND_IDEMPOTENCY_KEY, ex);
        }
    }

    public Optional<ToolCall> findActiveClaimByChat(Chat chat) {
        try {
            return getEntityManager().createQuery(
                    "select t from ToolCall t where t.chat = :chat and t.executionState = :state", ToolCall.class)
                    .setParameter("chat", chat)
                    .setParameter("state", ToolCall.ExecutionState.CLAIMED)
                    .getResultStream()
                    .findFirst();
        } catch (Exception ex) {
            throw new DAOException(ErrorKeys.FIND_ACTIVE_CLAIM, ex);
        }
    }

    public List<ToolCall> findByChat(Chat chat) {
        try {
            return getEntityManager().createQuery(
                    "select t from ToolCall t where t.chat = :chat order by t.sequence", ToolCall.class)
                    .setParameter("chat", chat)
                    .getResultList();
        } catch (Exception ex) {
            throw new DAOException(ErrorKeys.FIND_BY_CHAT, ex);
        }
    }

    public enum ErrorKeys {
        FIND_BY_CHAT_AND_ATTEMPT_ID,
        FIND_BY_CHAT_AND_IDEMPOTENCY_KEY,
        FIND_ACTIVE_CLAIM,
        FIND_BY_CHAT
    }
}
