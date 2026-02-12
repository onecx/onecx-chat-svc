package org.tkit.onecx.chat.domain.daos;

import static org.tkit.quarkus.jpa.utils.QueryCriteriaUtil.addSearchStringPredicate;

import java.util.ArrayList;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.criteria.Predicate;
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

            var cb = this.getEntityManager().getCriteriaBuilder();
            var cq = cb.createQuery(Message.class);
            var root = cq.from(Message.class);
            List<Predicate> predicates = new ArrayList<>();

            addSearchStringPredicate(predicates, cb, root.get(Message_.CHAT).get(TraceableEntity_.ID), criteria.getChatId());
            if (!predicates.isEmpty()) {
                cq.where(cb.and(predicates.toArray(new Predicate[0])));
            }
            cq.orderBy(cb.asc(root.get(Message_.CREATION_DATE)));

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
