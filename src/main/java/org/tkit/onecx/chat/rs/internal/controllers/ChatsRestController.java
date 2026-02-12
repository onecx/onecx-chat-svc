package org.tkit.onecx.chat.rs.internal.controllers;

import static jakarta.transaction.Transactional.TxType.NOT_SUPPORTED;

import java.util.*;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import org.jboss.resteasy.reactive.ClientWebApplicationException;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import org.tkit.onecx.chat.domain.daos.ChatDAO;
import org.tkit.onecx.chat.domain.daos.MessageDAO;
import org.tkit.onecx.chat.domain.daos.ParticipantDAO;
import org.tkit.onecx.chat.domain.models.Chat;
import org.tkit.onecx.chat.domain.models.Message;
import org.tkit.onecx.chat.domain.models.Participant;
import org.tkit.onecx.chat.rs.internal.mappers.ChatMapper;
import org.tkit.onecx.chat.rs.internal.mappers.ExceptionMapper;
import org.tkit.onecx.chat.rs.internal.services.ChatsService;
import org.tkit.quarkus.jpa.exceptions.ConstraintException;

import gen.org.tkit.onecx.chat.rs.internal.ChatsInternalApi;
import gen.org.tkit.onecx.chat.rs.internal.model.*;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ApplicationScoped
@Transactional(value = NOT_SUPPORTED)
public class ChatsRestController implements ChatsInternalApi {

    @Inject
    ChatsService service;

    @Inject
    ChatDAO dao;

    @Inject
    MessageDAO msgDao;

    @Inject
    ParticipantDAO participantDao;

    @Inject
    ChatMapper mapper;

    @Inject
    ExceptionMapper exceptionMapper;

    @Context
    UriInfo uriInfo;

    @Override
    public Response createChat(CreateChatDTO createChatDTO) {

        Chat chat = service.createChat(createChatDTO);

        return Response
                .created(uriInfo.getAbsolutePathBuilder().path(chat.getId()).build())
                .entity(mapper.mapChat(chat))
                .build();
    }

    @Override
    public Response deleteChat(String id) {
        service.deleteChat(id);
        return Response.noContent().build();
    }

    @Override
    public Response getChatById(String id) {
        var chat = dao.findById(id);
        if (chat == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(mapper.mapChat(chat)).build();
    }

    @Override
    public Response getChats(Integer pageNumber, Integer pageSize) {
        var items = dao.findAll(pageNumber, pageSize);
        return Response.ok(mapper.mapPage(items)).build();
    }

    @Override
    public Response searchChats(ChatSearchCriteriaDTO chatSearchCriteriaDTO) {
        var criteria = mapper.map(chatSearchCriteriaDTO);
        var result = dao.findChatsByCriteria(criteria);
        return Response.ok(mapper.mapPage(result)).build();
    }

    @Override
    public Response updateChat(String id, UpdateChatDTO updateChatDTO) {
        var chat = dao.findById(id);
        if (chat == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        service.updateChat(chat, updateChatDTO);
        return Response.noContent().build();
    }

    @Override
    public Response createChatMessage(String chatId, CreateMessageDTO createMessageDTO) {

        var chat = dao.findById(chatId);

        if (chat == null) {
            // Handle the case where chat or its messages are null
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        Message message = service.createChatMessage(chat, createMessageDTO);

        return Response
                .created(uriInfo.getAbsolutePathBuilder().path(message.getId()).build())
                .build();

    }

    @Override
    public Response getChatMessages(String chatId) {
        var chat = dao.findById(chatId);

        if (chat == null || chat.getMessages().isEmpty()) {
            // Handle the case where chat or its messages are null
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        var messages = chat.getMessages();
        List<Message> messageList = new ArrayList<>(messages);
        return Response.ok(mapper.mapMessageList(messageList)).build();

    }

    @Override
    public Response searchChatMessages(ChatMessageSearchCriteriaDTO chatMessageSearchCriteriaDTO) {
        var criteria = mapper.map(chatMessageSearchCriteriaDTO);
        var msgResult = msgDao.findChatMessagesByCriteria(criteria);
        List<Participant> participantsResult = participantDao.getParticipantsOfChatById(criteria.getChatId());
        return Response.ok(mapper.mapResponse(participantsResult, msgResult)).build();
    }

    @Override
    public Response addParticipant(String chatId, @Valid @NotNull AddParticipantDTO addParticipantDTO) {

        var chat = dao.findById(chatId);

        if (chat == null) {
            // Handle the case where chat or its messages are null
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        Participant participant = service.addParticipant(chat, addParticipantDTO);

        return Response
                .created(uriInfo.getAbsolutePathBuilder().path(participant.getId()).build())
                .build();

    }

    @Override
    public Response getChatParticipants(String chatId) {

        var chat = dao.findById(chatId);

        if (chat == null || chat.getParticipants().isEmpty()) {
            // Handle the case where chat or its messages are null
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        var participants = chat.getParticipants();
        List<Participant> participantList = new ArrayList<>(participants);
        return Response.ok(mapper.mapParticipantList(participantList)).build();

    }

    @ServerExceptionMapper
    public RestResponse<ProblemDetailResponseDTO> exception(ConstraintException ex) {
        return exceptionMapper.exception(ex);
    }

    @ServerExceptionMapper
    public RestResponse<ProblemDetailResponseDTO> constraint(ConstraintViolationException ex) {
        return exceptionMapper.constraint(ex);
    }

    @ServerExceptionMapper
    public RestResponse<ProblemDetailResponseDTO> restException(ClientWebApplicationException ex) {
        return exceptionMapper.clientException(ex);
    }
}
