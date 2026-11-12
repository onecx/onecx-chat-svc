package org.tkit.onecx.chat.rs.internal.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.security.Principal;
import java.util.List;
import java.util.Optional;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tkit.onecx.chat.domain.daos.ChatDAO;
import org.tkit.onecx.chat.domain.daos.ConversationEntryDAO;
import org.tkit.onecx.chat.domain.daos.ToolCallDAO;
import org.tkit.onecx.chat.domain.models.Chat;
import org.tkit.onecx.chat.domain.models.ConversationEntry;
import org.tkit.onecx.chat.domain.models.ToolCall;

import gen.org.tkit.onecx.chat.rs.internal.model.RegisterToolCallDTO;
import gen.org.tkit.onecx.chat.rs.internal.model.ToolCallClaimDTO;
import gen.org.tkit.onecx.chat.rs.internal.model.ToolCallDecisionDTO;
import gen.org.tkit.onecx.chat.rs.internal.model.ToolCallOutcomeDTO;
import gen.org.tkit.onecx.chat.rs.internal.model.ToolCallPolicyDecisionDTO;
import gen.org.tkit.onecx.chat.rs.internal.model.ToolCallTerminalDecisionDTO;
import gen.org.tkit.onecx.chat.rs.internal.model.ToolCallOutcomeStateDTO;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class ToolCallServiceTest {

    @Inject
    ToolCallService service;

    @InjectMock
    ToolCallDAO toolCallDAO;

    @InjectMock
    ChatDAO chatDAO;

    @InjectMock
    ConversationEntryDAO conversationEntryDAO;

    @InjectMock
    SecurityIdentity identity;

    private Chat chat;

    @BeforeEach
    void setUp() {
        chat = new Chat();
        chat.setId("chat-1");
        when(chatDAO.lock("chat-1")).thenReturn(chat);
        when(identity.getPrincipal()).thenReturn((Principal) () -> "runtime-1");
        when(conversationEntryDAO.findMaxSequenceByChat(chat)).thenReturn(0L, 1L, 2L);
        when(conversationEntryDAO.findByChatAndIdempotencyKey(any(), any())).thenReturn(Optional.empty());
        when(toolCallDAO.create(any(ToolCall.class))).thenAnswer(invocation -> {
            ToolCall created = invocation.getArgument(0);
            created.setId("attempt-guid");
            return created;
        });
    }

    @Test
    void deniedAttemptIsRecordedAsBlockedAndCannotBeClaimed() {
        RegisterToolCallDTO request = request(ToolCallPolicyDecisionDTO.DENY);

        var result = service.register(chat, request);

        assertThat(result.getExecutionState().name()).isEqualTo("BLOCKED");
        assertThat(result.getDecisionState().name()).isEqualTo("NOT_REQUIRED");
        verify(conversationEntryDAO, times(2)).create(any(ConversationEntry.class));
        verify(toolCallDAO, never()).findActiveClaimByChat(chat);
    }

    @Test
    void alwaysAskAttemptRemainsPendingAndCannotBeClaimedBeforeDecision() {
        RegisterToolCallDTO request = request(ToolCallPolicyDecisionDTO.ALWAYS_ASK);
        ToolCall call = pendingAttempt();
        var result = service.register(chat, request);
        when(toolCallDAO.findByChatAndAttemptId(chat, "attempt-1")).thenReturn(Optional.of(call));

        assertThat(result.getDecisionState().name()).isEqualTo("PENDING");
        ToolCallClaimDTO claim = new ToolCallClaimDTO();
        claim.setIdempotencyKey("claim-1");
        assertThrows(IdempotencyConflictException.class,
                () -> service.claim(chat, "attempt-1", claim));
        verify(toolCallDAO, never()).findActiveClaimByChat(chat);
    }

    @Test
    void allowedAttemptClaimIsPersistedBeforeItCanBeGranted() {
        RegisterToolCallDTO request = request(ToolCallPolicyDecisionDTO.ALLOW);
        service.register(chat, request);
        ToolCall call = new ToolCall();
        call.setChat(chat);
        call.setId("attempt-guid");
        call.setAttemptId("attempt-1");
        call.setPolicyDecision(ToolCall.PolicyDecision.ALLOW);
        call.setDecisionState(ToolCall.DecisionState.NOT_REQUIRED);
        call.setExecutionState(ToolCall.ExecutionState.NOT_STARTED);
        call.setArguments("{\"query\":\"safe\"}");
        when(toolCallDAO.findByChatAndAttemptId(chat, "attempt-1")).thenReturn(Optional.of(call));
        when(toolCallDAO.findActiveClaimByChat(chat)).thenReturn(Optional.empty());

        ToolCallClaimDTO claim = new ToolCallClaimDTO();
        claim.setIdempotencyKey("claim-idempotency");
        var response = service.claim(chat, "attempt-1", claim);

        assertThat(response.getNewlyGranted()).isTrue();
        assertThat(response.getArguments()).isEqualTo("{\"query\":\"safe\"}");
        assertThat(call.getExecutionState()).isEqualTo(ToolCall.ExecutionState.CLAIMED);
        assertThat(call.getClaimOwner()).isEqualTo("runtime-1");
        verify(toolCallDAO).update(call);
    }

    @Test
    void rejectedProposalAddsTerminalDecisionAndBlockedOutcome() {
        ToolCall call = pendingAttempt();
        when(toolCallDAO.findByChatAndAttemptId(chat, "attempt-1")).thenReturn(Optional.of(call));
        when(toolCallDAO.update(call)).thenReturn(call);
        ToolCallDecisionDTO request = new ToolCallDecisionDTO();
        request.setIdempotencyKey("decision-1");
        request.setDecision(ToolCallTerminalDecisionDTO.REJECTED);

        var result = service.decide(chat, "attempt-1", request);

        assertThat(result.getDecisionState().name()).isEqualTo("REJECTED");
        assertThat(result.getExecutionState().name()).isEqualTo("BLOCKED");
        assertThat(result.getBlockedReason().name()).isEqualTo("PROPOSAL_REJECTED");
        verify(conversationEntryDAO, times(2)).create(any(ConversationEntry.class));
    }

    @Test
    void approvedProposalCanBeClaimedAfterItsDecisionIsAppended() {
        ToolCall call = pendingAttempt();
        when(toolCallDAO.findByChatAndAttemptId(chat, "attempt-1")).thenReturn(Optional.of(call));
        when(toolCallDAO.update(call)).thenReturn(call);
        when(toolCallDAO.findActiveClaimByChat(chat)).thenReturn(Optional.empty());
        ToolCallDecisionDTO decision = new ToolCallDecisionDTO();
        decision.setIdempotencyKey("decision-1");
        decision.setDecision(ToolCallTerminalDecisionDTO.APPROVED);

        service.decide(chat, "attempt-1", decision);

        assertThat(call.getDecisionState()).isEqualTo(ToolCall.DecisionState.APPROVED);
        assertThat(call.getExecutionState()).isEqualTo(ToolCall.ExecutionState.NOT_STARTED);
        ToolCallClaimDTO claim = new ToolCallClaimDTO();
        claim.setIdempotencyKey("claim-1");
        var claimResponse = service.claim(chat, "attempt-1", claim);

        assertThat(claimResponse.getNewlyGranted()).isTrue();
        assertThat(call.getExecutionState()).isEqualTo(ToolCall.ExecutionState.CLAIMED);
    }

    @Test
    void registrationRetryMustKeepAllBoundRequestData() {
        RegisterToolCallDTO request = request(ToolCallPolicyDecisionDTO.ALLOW);
        ToolCall call = new ToolCall();
        call.setAttemptId(request.getAttemptId());
        call.setIdempotencyKey(request.getIdempotencyKey());
        call.setToolName(request.getToolName());
        call.setArguments(request.getArguments());
        call.setConfigurationSnapshotHash(request.getConfigurationSnapshotHash());
        call.setSchemaHash(request.getSchemaHash());
        call.setArgumentHash(request.getArgumentHash());
        call.setProposingPrincipal(request.getProposingPrincipal());
        call.setRuntime(request.getRuntime());
        call.setPolicyDecision(ToolCall.PolicyDecision.ALLOW);
        call.setDecisionState(ToolCall.DecisionState.NOT_REQUIRED);
        call.setExecutionState(ToolCall.ExecutionState.NOT_STARTED);
        call.setSafeSummary(request.getSafeSummary());
        call.setSequence(1L);
        when(toolCallDAO.findByChatAndIdempotencyKey(chat, request.getIdempotencyKey()))
                .thenReturn(Optional.of(call));

        assertThat(service.register(chat, request).getAttemptId()).isEqualTo(request.getAttemptId());
        request.setArguments("{\"query\":\"changed\"}");
        assertThrows(IdempotencyConflictException.class, () -> service.register(chat, request));
    }

    @Test
    void onlyClaimOwnerCanReportOutcomeAndSafeResultDoesNotExposeExactPayload() {
        ToolCall call = pendingAttempt();
        call.setDecisionState(ToolCall.DecisionState.NOT_REQUIRED);
        call.setPolicyDecision(ToolCall.PolicyDecision.ALLOW);
        call.setExecutionState(ToolCall.ExecutionState.CLAIMED);
        call.setClaimId("claim-id");
        call.setClaimOwner("runtime-1");
        when(toolCallDAO.findByChatAndAttemptId(chat, "attempt-1")).thenReturn(Optional.of(call));
        when(toolCallDAO.update(call)).thenReturn(call);
        ToolCallOutcomeDTO request = new ToolCallOutcomeDTO();
        request.setIdempotencyKey("outcome-1");
        request.setClaimId("claim-id");
        request.setOutcome(ToolCallOutcomeStateDTO.SUCCEEDED);
        request.setSafeSummary("Search completed");
        request.setExactResult("{\"private\":\"model-only\"}");

        var safeResponse = service.reportOutcome(chat, "attempt-1", request);

        assertThat(safeResponse.getSafeSummary()).isEqualTo("Search completed");
        assertThat(safeResponse.toString()).doesNotContain("model-only");
        assertThat(call.getExactResult()).isEqualTo("{\"private\":\"model-only\"}");
        when(identity.getPrincipal()).thenReturn((Principal) () -> "other-runtime");
        assertThrows(IdempotencyConflictException.class,
                () -> service.reportOutcome(chat, "attempt-1", request));
    }

    @Test
    void runtimeReplayUsesEntryOrderAndReturnsExactResultOnlyOnResultEvent() {
        ToolCall call = pendingAttempt();
        call.setArguments("{\"private\":\"arguments\"}");
        call.setExactResult("{\"private\":\"result\"}");
        ConversationEntry request = toolEvent(1L, ConversationEntry.EntryType.TOOL_REQUEST, "request");
        ConversationEntry result = toolEvent(2L, ConversationEntry.EntryType.TOOL_RESULT, "result");
        when(toolCallDAO.findByChat(chat)).thenReturn(List.of(call));
        when(conversationEntryDAO.findReplayEntries(chat)).thenReturn(List.of(request, result));

        var replay = service.runtimeReplay(chat);

        assertThat(replay).hasSize(2);
        assertThat(replay.get(0).getSequenceNumber()).isEqualTo(1L);
        assertThat(replay.get(0).getArguments()).isEqualTo("{\"private\":\"arguments\"}");
        assertThat(replay.get(0).getExactResult()).isNull();
        assertThat(replay.get(1).getSequenceNumber()).isEqualTo(2L);
        assertThat(replay.get(1).getExactResult()).isEqualTo("{\"private\":\"result\"}");
    }

    private ToolCall pendingAttempt() {
        ToolCall call = new ToolCall();
        call.setChat(chat);
        call.setId("attempt-guid");
        call.setAttemptId("attempt-1");
        call.setPolicyDecision(ToolCall.PolicyDecision.ALWAYS_ASK);
        call.setDecisionState(ToolCall.DecisionState.PENDING);
        call.setExecutionState(ToolCall.ExecutionState.NOT_STARTED);
        call.setToolName("search");
        call.setRuntime("provider-runtime");
        return call;
    }

    private ConversationEntry toolEvent(long sequence, ConversationEntry.EntryType type, String key) {
        ConversationEntry entry = new ConversationEntry();
        entry.setSequence(sequence);
        entry.setType(type);
        entry.setIdempotencyKey(key);
        entry.setCausalReference("attempt-1");
        entry.setText(key);
        return entry;
    }

    private RegisterToolCallDTO request(ToolCallPolicyDecisionDTO decision) {
        RegisterToolCallDTO request = new RegisterToolCallDTO();
        request.setAttemptId("attempt-1");
        request.setIdempotencyKey("request-1");
        request.setToolName("search");
        request.setArguments("{\"query\":\"safe\"}");
        request.setConfigurationSnapshotHash("configuration-hash");
        request.setSchemaHash("schema-hash");
        request.setArgumentHash("argument-hash");
        request.setProposingPrincipal("user-1");
        request.setRuntime("provider-runtime");
        request.setPolicyDecision(decision);
        request.setSafeSummary("Search request");
        return request;
    }
}
