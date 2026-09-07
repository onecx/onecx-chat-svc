package org.tkit.onecx.chat.rs.internal.controllers;

import static jakarta.transaction.Transactional.TxType.NOT_SUPPORTED;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import org.tkit.onecx.chat.domain.daos.ChatDAO;
import org.tkit.onecx.chat.rs.internal.mappers.ConversationEntryMapper;
import org.tkit.onecx.chat.rs.internal.mappers.ExceptionMapper;
import org.tkit.onecx.chat.rs.internal.services.ConversationEntryService;

import gen.org.tkit.onecx.chat.rs.internal.ConversationEntriesInternalApi;
import gen.org.tkit.onecx.chat.rs.internal.model.CreateOrUpdateConversationEntryDTO;
import gen.org.tkit.onecx.chat.rs.internal.model.ProblemDetailResponseDTO;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ApplicationScoped
@Transactional(value = NOT_SUPPORTED)
public class ConversationEntriesRestController implements ConversationEntriesInternalApi {

    @Inject
    ChatDAO dao;

    @Inject
    ConversationEntryService service;

    @Inject
    ConversationEntryMapper mapper;

    @Inject
    ExceptionMapper exceptionMapper;

    @Context
    UriInfo uriInfo;

    @Override
    public Response createOrUpdateConversationEntry(String chatId,
            CreateOrUpdateConversationEntryDTO createOrUpdateConversationEntryDTO) {
        var chat = dao.findById(chatId);
        if (chat == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        try {
            var outcome = service.createOrUpdate(chat, createOrUpdateConversationEntryDTO);
            var dto = mapper.map(outcome.getEntry());

            if (outcome.isCreated()) {
                return Response
                        .created(uriInfo.getAbsolutePathBuilder().path(outcome.getEntry().getId()).build())
                        .entity(dto)
                        .build();
            }
            return Response.ok(dto).build();
        } catch (ConversationEntryService.ConversationEntryConflictException ex) {
            return Response.status(Response.Status.CONFLICT).build();
        }
    }

    @Override
    public Response getConversationEntries(String chatId) {
        var chat = dao.findById(chatId);
        if (chat == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(mapper.mapList(service.replay(chat))).build();
    }

    @ServerExceptionMapper
    public RestResponse<ProblemDetailResponseDTO> restException(Exception ex) {
        return exceptionMapper.genericException(ex);
    }
}
