package org.tkit.onecx.chat.domain.daos;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import org.tkit.onecx.chat.domain.models.Participant;
import org.tkit.onecx.chat.domain.models.Participant_;
import org.tkit.quarkus.jpa.daos.AbstractDAO;
import org.tkit.quarkus.jpa.exceptions.DAOException;

@ApplicationScoped
@Transactional(Transactional.TxType.REQUIRED)
public class ParticipantDAO extends AbstractDAO<Participant> {

    public List<Participant> getParticipantsOfChatById(String chatId) {
        try {
            var cb = this.getEntityManager().getCriteriaBuilder();
            var cq = cb.createQuery(Participant.class);
            var participantRoot = cq.from(Participant.class);
            var chats = participantRoot.join(Participant_.chats);

            cq.select(participantRoot).where(cb.equal(chats.get("id"), chatId)).distinct(true);

            return this.getEntityManager().createQuery(cq).getResultList();
        } catch (Exception ex) {
            throw new DAOException(ParticipantDAO.ErrorKeys.ERROR_FIND_PARTICIPANTS_BY_CHAT_ID, ex);
        }
    }

    public enum ErrorKeys {

        ERROR_CREATE_PARTICIPANT,
        ERROR_FIND_PARTICIPANTS_BY_CHAT_ID
    }
}
