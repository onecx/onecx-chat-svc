package org.tkit.onecx.chat.domain.daos;

import java.util.ArrayList;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityGraph;
import jakarta.persistence.NoResultException;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.transaction.Transactional;

import org.tkit.onecx.chat.domain.criteria.ChatSearchCriteria;
import org.tkit.onecx.chat.domain.models.Chat;
import org.tkit.onecx.chat.domain.models.Chat_;
import org.tkit.onecx.chat.domain.models.Participant;
import org.tkit.quarkus.jpa.daos.AbstractDAO;
import org.tkit.quarkus.jpa.daos.Page;
import org.tkit.quarkus.jpa.daos.PageResult;
import org.tkit.quarkus.jpa.exceptions.DAOException;
import org.tkit.quarkus.jpa.models.TraceableEntity_;
import org.tkit.quarkus.jpa.utils.QueryCriteriaUtil;

@ApplicationScoped
@Transactional(Transactional.TxType.NOT_SUPPORTED)
public class ChatDAO extends AbstractDAO<Chat> {

    // https://hibernate.atlassian.net/browse/HHH-16830#icft=HHH-16830
    @Override
    public Chat findById(Object id) throws DAOException {
        try {
            var cb = this.getEntityManager().getCriteriaBuilder();
            var cq = cb.createQuery(Chat.class);
            var root = cq.from(Chat.class);
            cq.where(cb.equal(root.get(TraceableEntity_.ID), id));

            EntityGraph graph = this.em.getEntityGraph(Chat.CHAT_LOAD);

            return this.getEntityManager().createQuery(cq).setHint(HINT_LOAD_GRAPH, graph).getSingleResult();
        } catch (NoResultException nre) {
            return null;
        } catch (Exception e) {
            throw new DAOException(ErrorKeys.FIND_ENTITY_BY_ID_FAILED, e, entityName, id);
        }
    }

    public PageResult<Chat> findChatsByCriteria(ChatSearchCriteria criteria) {
        try {
            var cb = this.getEntityManager().getCriteriaBuilder();
            var cq = cb.createQuery(Chat.class);
            var root = cq.from(Chat.class);

            List<Predicate> predicates = new ArrayList<>();

            if (criteria.getTopic() != null && !criteria.getTopic().isBlank()) {
                predicates.add(cb.like(root.get(Chat_.topic), QueryCriteriaUtil.wildcard(criteria.getTopic())));
            }

            if (criteria.getType() != null) {
                predicates.add(cb.equal(root.get(Chat_.type), criteria.getType()));
            }

            if (criteria.getParticipant() != null) {
                Join<Chat, Participant> participantsJoin = root.join("participants");
                predicates.add(cb.equal(participantsJoin.get("userId"), criteria.getParticipant()));
            }

            if (!predicates.isEmpty()) {
                cq.where(cb.and(predicates.toArray(new Predicate[0])));
            }

            return createPageQuery(cq, Page.of(criteria.getPageNumber(), criteria.getPageSize())).getPageResult();
        } catch (Exception ex) {
            throw new DAOException(ErrorKeys.ERROR_FIND_CHATS_BY_CRITERIA, ex);
        }
    }

    public PageResult<Chat> findAll(Integer pageNumber, Integer pageSize) {
        try {
            var cb = this.getEntityManager().getCriteriaBuilder();
            var cq = cb.createQuery(Chat.class);
            cq.from(Chat.class);
            return createPageQuery(cq, Page.of(pageNumber, pageSize)).getPageResult();
        } catch (Exception ex) {
            throw new DAOException(ErrorKeys.ERROR_FIND_ALL_CHAT_PAGE, ex);
        }
    }

    public enum ErrorKeys {

        FIND_ENTITY_BY_ID_FAILED,
        ERROR_FIND_CHATS_BY_CRITERIA,
        ERROR_FIND_ALL_CHAT_PAGE,
    }
}
