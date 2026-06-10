package org.tkit.onecx.chat.rs.internal.services;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.eclipse.microprofile.context.ManagedExecutor;
import org.eclipse.microprofile.context.ThreadContext;
import org.tkit.onecx.chat.domain.daos.ChatDAO;
import org.tkit.onecx.chat.domain.daos.MessageDAO;
import org.tkit.onecx.chat.domain.daos.ParticipantDAO;
import org.tkit.onecx.chat.domain.models.Chat;
import org.tkit.onecx.chat.domain.models.Message;
import org.tkit.onecx.chat.domain.models.Participant;
import org.tkit.onecx.chat.rs.internal.mappers.ChatMapper;
import org.tkit.onecx.chat.rs.internal.mappers.ExceptionMapper;

import gen.org.tkit.onecx.chat.rs.internal.model.*;
import io.smallrye.context.api.ManagedExecutorConfig;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Transactional(Transactional.TxType.NOT_SUPPORTED)
@ApplicationScoped
public class ChatsService {

    @Inject
    ChatDAO dao;

    @Inject
    ParticipantDAO participantDao;

    @Inject
    MessageDAO msgDao;

    @Inject
    ChatMapper mapper;

    @Inject
    ExceptionMapper exceptionMapper;

    @Inject
    ParticipantService participantService;

    @Inject
    @ManagedExecutorConfig(cleared = ThreadContext.ALL_REMAINING, propagated = {})
    ManagedExecutor managedExecutor;

    @Inject
    AsyncAiProcessingService asyncAiProcessingService;

    @Transactional
    public Chat createChat(CreateChatDTO createChatDTO) {
        var chat = mapper.create(createChatDTO);
        chat = dao.create(chat);

        if (!createChatDTO.getParticipants().isEmpty()) {
            for (ParticipantDTO participantDTO : createChatDTO.getParticipants()) {
                var chatParticipant = participantService.getNewOrUpdatedParticipant(participantDTO);
                chat.getParticipants().add(chatParticipant);
                chatParticipant.getChats().add(chat);
                participantDao.update(chatParticipant);
            }
        }
        chat = dao.update(chat);
        return chat;
    }

    @Transactional
    public void deleteChat(String id) {
        dao.deleteQueryById(id);
    }

    @Transactional
    public void updateChat(Chat chat, UpdateChatDTO updateChatDTO) {
        mapper.update(updateChatDTO, chat);
        dao.update(chat);
    }

    @Transactional
    public Message createChatMessage(Chat chat, CreateMessageDTO createMessageDTO) {
        var message = mapper.createMessage(createMessageDTO);
        message.setChat(chat);
        message = msgDao.create(message);
        var skipAiProcessing = Optional.ofNullable(createMessageDTO.getSkipAIProcessing()).orElse(false);
        var awaitResponse = !Boolean.FALSE.equals(createMessageDTO.getAwaitResponse());

        if (shouldForwardToAiService(chat.getType(), skipAiProcessing)) {
            if (awaitResponse) {
                asyncAiProcessingService.forwardToAiAndStore(chat, message);
            } else {
                spawnAsyncAiProcessing(chat.getId(), message.getId());
            }
        }
        return message;
    }

    private void spawnAsyncAiProcessing(String chatId, String messageId) {
        managedExecutor.runAsync(() -> {
            try {
                asyncAiProcessingService.process(chatId, messageId);
                log.debug("Async AI processing completed for chatId={}", chatId);
            } catch (Exception ex) {
                log.error("Async AI response processing failed for chatId={}, messageId={}", chatId, messageId, ex);
            }
        });
    }

    @Transactional
    public Participant addParticipant(Chat chat, AddParticipantDTO addParticipantDTO) {
        var participant = participantService.getNewOrUpdatedParticipant(addParticipantDTO);
        if (participant.getChats().contains(chat)) {
            return participant;
        }
        participant.getChats().add(chat);
        chat.getParticipants().add(participant);
        participant = participantDao.update(participant);
        dao.update(chat);
        return participant;
    }

    private boolean shouldForwardToAiService(final Chat.ChatType chatType, final boolean skipProcessing) {
        return chatType.equals(Chat.ChatType.AI_CHAT) && !skipProcessing;
    }
}
