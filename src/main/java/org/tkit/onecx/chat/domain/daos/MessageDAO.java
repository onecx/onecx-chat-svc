package org.tkit.onecx.chat.domain.daos;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import org.tkit.onecx.chat.domain.criteria.ChatMessageSearchCriteria;
import org.tkit.onecx.chat.domain.models.Chat_;
import org.tkit.onecx.chat.domain.models.Message;
import org.tkit.onecx.chat.domain.models.Message_;
import org.tkit.quarkus.jpa.daos.AbstractDAO;
import org.tkit.quarkus.jpa.daos.Page;
import org.tkit.quarkus.jpa.daos.PageResult;
import org.tkit.quarkus.jpa.exceptions.DAOException;

@ApplicationScoped
@Transactional(Transactional.TxType.NOT_SUPPORTED)
public class MessageDAO extends AbstractDAO<Message> {

    public PageResult<Message> findChatMessagesByCriteria(ChatMessageSearchCriteria criteria) {
        try {
            if (criteria == null) {
                throw new DAOException(MessageDAO.ErrorKeys.ERROR_FIND_CHAT_MESSAGES_BY_CRITERIA,
                        new NullPointerException("Criteria is null"));
            }

            if (criteria.getChatId() == null || criteria.getChatId().isBlank()) {
                return PageResult.empty();
            }

            var cb = this.getEntityManager().getCriteriaBuilder();
            var cq = cb.createQuery(Message.class);
            var root = cq.from(Message.class);

            cq.where(
                    cb.equal(
                            root.get(Message_.chat).get(Chat_.id),
                            criteria.getChatId()));

            int pageNumber = criteria.getPageNumber() != null ? criteria.getPageNumber() : 0;
            int pageSize = criteria.getPageSize() != null ? criteria.getPageSize() : 20;
            if (pageSize > 50) {
                pageSize = 50;
            }

            return createPageQuery(cq, Page.of(pageNumber, pageSize)).getPageResult();

        } catch (DAOException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new DAOException(MessageDAO.ErrorKeys.ERROR_FIND_CHAT_MESSAGES_BY_CRITERIA, ex);
        }
    }

    public enum ErrorKeys {

        ERROR_CREATE_MESSAGE,
        ERROR_FIND_CHAT_MESSAGES_BY_CRITERIA
    }
}
