package org.tkit.onecx.chat.rs.internal.controllers;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;

import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import org.tkit.onecx.chat.domain.daos.ChatDAO;
import org.tkit.onecx.chat.domain.models.Chat;
import org.tkit.onecx.chat.rs.internal.services.IdempotencyConflictException;
import org.tkit.onecx.chat.rs.internal.services.ToolCallNotFoundException;
import org.tkit.onecx.chat.rs.internal.services.ToolCallService;

import gen.org.tkit.onecx.chat.rs.internal.model.ProblemDetailResponseDTO;
import gen.org.tkit.onecx.chat.rs.internal.model.RegisterToolCallDTO;
import gen.org.tkit.onecx.chat.rs.internal.model.ToolCallClaimDTO;
import gen.org.tkit.onecx.chat.rs.internal.model.ToolCallClaimResponseDTO;
import gen.org.tkit.onecx.chat.rs.internal.model.ToolCallContinuationDTO;
import gen.org.tkit.onecx.chat.rs.internal.model.ToolCallDecisionDTO;
import gen.org.tkit.onecx.chat.rs.internal.model.ToolCallOutcomeDTO;
import io.quarkus.security.PermissionsAllowed;

@ApplicationScoped
@Path("/internal/chats/{chatId}/tool-calls")
@Consumes(APPLICATION_JSON)
@Produces(APPLICATION_JSON)
@Transactional(Transactional.TxType.NOT_SUPPORTED)
public class ToolCallsRestController {

    @Inject
    ChatDAO chatDAO;

    @Inject
    ToolCallService service;

    @POST
    @PermissionsAllowed("ocx-chat:tool-runtime")
    public Response register(@PathParam("chatId") String chatId, @Valid RegisterToolCallDTO request) {
        Chat chat = chatDAO.findById(chatId);
        if (chat == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.status(Response.Status.CREATED).entity(service.register(chat, request)).build();
    }

    @GET
    @PermissionsAllowed(value = { "ocx-chat:all", "ocx-chat:read" }, inclusive = false)
    public Response history(@PathParam("chatId") String chatId) {
        Chat chat = chatDAO.findById(chatId);
        if (chat == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(service.history(chat)).build();
    }

    @POST
    @Path("{attemptId}/decision")
    @PermissionsAllowed(value = { "ocx-chat:all", "ocx-chat:write" }, inclusive = false)
    public Response decide(@PathParam("chatId") String chatId, @PathParam("attemptId") String attemptId,
            @Valid ToolCallDecisionDTO request) {
        Chat chat = chatDAO.findById(chatId);
        if (chat == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(service.decide(chat, attemptId, request)).build();
    }

    @POST
    @Path("{attemptId}/claims")
    @PermissionsAllowed("ocx-chat:tool-runtime")
    public Response claim(@PathParam("chatId") String chatId, @PathParam("attemptId") String attemptId,
            @Valid ToolCallClaimDTO request) {
        Chat chat = chatDAO.findById(chatId);
        if (chat == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        ToolCallClaimResponseDTO claim = service.claim(chat, attemptId, request);
        return Response.status(claim.getNewlyGranted()
                ? Response.Status.CREATED
                : Response.Status.OK).entity(claim).build();
    }

    @POST
    @Path("{attemptId}/outcome")
    @PermissionsAllowed("ocx-chat:tool-runtime")
    public Response outcome(@PathParam("chatId") String chatId, @PathParam("attemptId") String attemptId,
            @Valid ToolCallOutcomeDTO request) {
        Chat chat = chatDAO.findById(chatId);
        if (chat == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(service.reportOutcome(chat, attemptId, request)).build();
    }

    @POST
    @Path("{attemptId}/continuations")
    @PermissionsAllowed("ocx-chat:tool-runtime")
    public Response continuation(@PathParam("chatId") String chatId, @PathParam("attemptId") String attemptId,
            @Valid ToolCallContinuationDTO request) {
        Chat chat = chatDAO.findById(chatId);
        if (chat == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        service.appendContinuation(chat, attemptId, request);
        return Response.status(Response.Status.CREATED).build();
    }

    @GET
    @Path("runtime-replay")
    @PermissionsAllowed("ocx-chat:tool-runtime")
    public Response runtimeReplay(@PathParam("chatId") String chatId) {
        Chat chat = chatDAO.findById(chatId);
        if (chat == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(service.runtimeReplay(chat)).build();
    }

    @ServerExceptionMapper
    public RestResponse<ProblemDetailResponseDTO> conflict(IdempotencyConflictException ex) {
        return RestResponse.status(Response.Status.CONFLICT, problem("LIFECYCLE_CONFLICT", ex.getMessage()));
    }

    @ServerExceptionMapper
    public RestResponse<ProblemDetailResponseDTO> invalidState(IllegalStateException ex) {
        return RestResponse.status(Response.Status.CONFLICT, problem("INVALID_STATE", ex.getMessage()));
    }

    @ServerExceptionMapper
    public RestResponse<ProblemDetailResponseDTO> notFound(ToolCallNotFoundException ex) {
        return RestResponse.status(Response.Status.NOT_FOUND, problem("TOOL_CALL_NOT_FOUND", ex.getMessage()));
    }

    private ProblemDetailResponseDTO problem(String errorCode, String detail) {
        ProblemDetailResponseDTO dto = new ProblemDetailResponseDTO();
        dto.setErrorCode(errorCode);
        dto.setDetail(detail);
        return dto;
    }
}
