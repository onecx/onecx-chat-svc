package org.tkit.onecx.chat.domain.daos;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import org.tkit.onecx.chat.domain.models.Participant;
import org.tkit.onecx.chat.domain.models.Participant_;
import org.tkit.quarkus.jpa.daos.AbstractDAO;
import org.tkit.quarkus.jpa.exceptions.DAOException;
import org.tkit.quarkus.jpa.models.TraceableEntity_;

@ApplicationScoped
@Transactional(Transactional.TxType.REQUIRED)
public class ParticipantDAO extends AbstractDAO<Participant> {

    public List<Participant> getParticipantsOfChatById(String chatId) {
        try {
            var cb = this.getEntityManager().getCriteriaBuilder();
            var cq = cb.createQuery(Participant.class);
            var root = cq.from(Participant.class);

            cq.where(cb.equal(root.get(Participant_.CHATS).get(TraceableEntity_.ID), chatId))
                    .distinct(true);

            return this.getEntityManager().createQuery(cq).setHint(HINT_LOAD_GRAPH,
                    this.getEntityManager().getEntityGraph(Participant.CHAT_LOAD)).getResultList();
        } catch (Exception ex) {
            throw new DAOException(ParticipantDAO.ErrorKeys.ERROR_FIND_PARTICIPANTS_BY_CHAT_ID, ex);
        }
    }

    public enum ErrorKeys {

        ERROR_CREATE_PARTICIPANT,
        ERROR_FIND_PARTICIPANTS_BY_CHAT_ID
    }
}
