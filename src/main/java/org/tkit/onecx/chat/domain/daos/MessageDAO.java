package org.tkit.onecx.chat.domain.daos;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import org.tkit.onecx.chat.domain.criteria.ChatMessageSearchCriteria;
import org.tkit.onecx.chat.domain.models.Message;
import org.tkit.onecx.chat.domain.models.Message_;
import org.tkit.quarkus.jpa.daos.AbstractDAO;
import org.tkit.quarkus.jpa.daos.Page;
import org.tkit.quarkus.jpa.daos.PageResult;
import org.tkit.quarkus.jpa.exceptions.DAOException;
import org.tkit.quarkus.jpa.models.TraceableEntity_;

@ApplicationScoped
@Transactional(Transactional.TxType.NOT_SUPPORTED)
public class MessageDAO extends AbstractDAO<Message> {

    public PageResult<Message> findChatMessagesByCriteria(ChatMessageSearchCriteria criteria) {
        try {

            if (criteria.getChatId() == null || criteria.getChatId().isBlank()) {
                return PageResult.empty();
            }

            var cb = this.getEntityManager().getCriteriaBuilder();
            var cq = cb.createQuery(Message.class);
            var root = cq.from(Message.class);

            cq.where(
                    cb.equal(
                            root.get(Message_.chat).get(TraceableEntity_.id),
                            criteria.getChatId()));

            return createPageQuery(cq, Page.of(criteria.getPageNumber(), criteria.getPageSize())).getPageResult();

        } catch (Exception ex) {
            throw new DAOException(MessageDAO.ErrorKeys.ERROR_FIND_CHAT_MESSAGES_BY_CRITERIA, ex);
        }
    }

    public enum ErrorKeys {

        ERROR_CREATE_MESSAGE,
        ERROR_FIND_CHAT_MESSAGES_BY_CRITERIA
    }
}
