package org.tkit.onecx.chat.rs.internal.services;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.tkit.onecx.chat.domain.daos.ChatDAO;
import org.tkit.onecx.chat.domain.daos.MessageDAO;
import org.tkit.onecx.chat.domain.daos.ParticipantDAO;
import org.tkit.onecx.chat.domain.models.Chat;
import org.tkit.onecx.chat.domain.models.Message;
import org.tkit.onecx.chat.domain.models.Participant;
import org.tkit.onecx.chat.rs.internal.mappers.ChatMapper;
import org.tkit.onecx.chat.rs.internal.mappers.ExceptionMapper;

import gen.io.github.onecx.ai.clients.api.DispatchApi;
import gen.io.github.onecx.ai.clients.model.ChatMessage;
import gen.io.github.onecx.ai.clients.model.ChatRequest;
import gen.io.github.onecx.ai.clients.model.Conversation;
import gen.org.tkit.onecx.chat.rs.internal.model.*;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Transactional(Transactional.TxType.NOT_SUPPORTED)
@ApplicationScoped
public class ChatsService {

    @Inject
    @RestClient
    DispatchApi dispatchClient;

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

    @Transactional
    public Chat createChat(CreateChatDTO createChatDTO) {
        var chat = mapper.create(createChatDTO);
        chat = dao.create(chat);

        if (createChatDTO.getParticipants() != null && !createChatDTO.getParticipants().isEmpty()) {
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

        if (Chat.ChatType.AI_CHAT.equals(chat.getType())) {

            Conversation conversation = mapper.mapChat2Conversation(chat);
            ChatMessage chatMessage = mapper.mapMessage(message);

            ChatRequest chatRequest = new ChatRequest();
            chatRequest.chatMessage(chatMessage);
            chatRequest.conversation(conversation);

            try (Response response = dispatchClient.chat(chatRequest)) {
                var chatResponse = response.readEntity(ChatMessage.class);
                var responseMessage = mapper.mapAiSvcMessage(chatResponse);
                responseMessage.setChat(chat);
                msgDao.create(responseMessage);
            }
        }
        return message;
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
}
