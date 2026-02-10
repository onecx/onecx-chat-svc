package org.tkit.onecx.chat.domain.daos;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import org.tkit.onecx.chat.domain.models.Participant;
import org.tkit.quarkus.jpa.daos.AbstractDAO;

@ApplicationScoped
@Transactional(Transactional.TxType.REQUIRED)
public class ParticipantDAO extends AbstractDAO<Participant> {

    public enum ErrorKeys {

        ERROR_CREATE_PARTICIPANT,
    }
}
