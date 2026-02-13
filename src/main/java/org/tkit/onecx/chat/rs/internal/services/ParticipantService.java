package org.tkit.onecx.chat.rs.internal.services;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.tkit.onecx.chat.domain.daos.ParticipantDAO;
import org.tkit.onecx.chat.domain.models.Participant;
import org.tkit.onecx.chat.rs.internal.mappers.ChatMapper;

import gen.org.tkit.onecx.chat.rs.internal.model.AddParticipantDTO;
import gen.org.tkit.onecx.chat.rs.internal.model.ParticipantDTO;

@ApplicationScoped
public class ParticipantService {

    @Inject
    ParticipantDAO participantDAO;

    @Inject
    ChatMapper mapper;

    public Participant getNewOrUpdatedParticipant(final AddParticipantDTO addParticipantDTO) {
        var participantDto = mapper.mapToParticipantDTO(addParticipantDTO);
        return getNewOrUpdatedParticipant(participantDto);
    }

    public Participant getNewOrUpdatedParticipant(final ParticipantDTO newParticipant) {
        var optionalParticipant = getExistingParticipant(newParticipant.getUserId());
        if (optionalParticipant.isEmpty()) {
            var createdParticipant = mapper.mapParticipant(newParticipant);
            return participantDAO.create(createdParticipant);
        }
        var existingParticipant = optionalParticipant.get();
        if (participantNeedsUpdate(newParticipant, existingParticipant)) {
            mapper.updateParticipant(existingParticipant, newParticipant);
            existingParticipant = participantDAO.update(existingParticipant);
        }
        return existingParticipant;
    }

    private Optional<Participant> getExistingParticipant(final String userId) {
        return participantDAO.getParticipantByUserId(userId);
    }

    private boolean participantNeedsUpdate(final ParticipantDTO newParticipant, final Participant existingParticipant) {
        return !newParticipant.getEmail().equals(existingParticipant.getEmail()) ||
                !newParticipant.getUserName().equals(existingParticipant.getUserName());
    }
}
