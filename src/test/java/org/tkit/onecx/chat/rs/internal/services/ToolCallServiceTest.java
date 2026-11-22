package org.tkit.onecx.chat.rs.internal.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.security.Principal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import gen.org.tkit.onecx.chat.rs.internal.model.ToolCallContinuationDTO;
import gen.org.tkit.onecx.chat.rs.internal.model.ToolCallDecisionDTO;
import gen.org.tkit.onecx.chat.rs.internal.model.ToolCallOutcomeDTO;
import gen.org.tkit.onecx.chat.rs.internal.model.ToolCallOutcomeStateDTO;
import gen.org.tkit.onecx.chat.rs.internal.model.ToolCallPolicyDecisionDTO;
import gen.org.tkit.onecx.chat.rs.internal.model.ToolCallTerminalDecisionDTO;
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
        call.setRequestSafeSummary(request.getSafeSummary());
        call.setSequence(1L);
        when(toolCallDAO.findByChatAndIdempotencyKey(chat, request.getIdempotencyKey()))
                .thenReturn(Optional.of(call));

        assertThat(service.register(chat, request).getAttemptId()).isEqualTo(request.getAttemptId());
        call.setSafeSummary("Execution completed");
        call.setExecutionState(ToolCall.ExecutionState.SUCCEEDED);
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
    void runtimeReplayPreservesMixedRuntimeLifecycleOrderAndHidesExactResults() {
        ToolCall providerCall = pendingAttempt();
        providerCall.setArguments("{\"private\":\"provider-arguments\"}");
        providerCall.setRuntime("provider-runtime");
        ToolCall pipecatCall = pendingAttempt();
        pipecatCall.setAttemptId("attempt-2");
        pipecatCall.setArguments("{\"private\":\"pipecat-arguments\"}");
        pipecatCall.setExactResult("{\"private\":\"pipecat-result\"}");
        pipecatCall.setRuntime("pipecat");
        List<ConversationEntry> entries = List.of(
                toolEvent(1L, ConversationEntry.EntryType.TOOL_REQUEST, "request-provider", "attempt-1"),
                toolEvent(2L, ConversationEntry.EntryType.TOOL_DECISION, "decision-provider", "attempt-1"),
                toolEvent(3L, ConversationEntry.EntryType.TOOL_REQUEST, "request-pipecat", "attempt-2"),
                toolEvent(4L, ConversationEntry.EntryType.TOOL_EXECUTION, "execution-pipecat", "attempt-2"),
                toolEvent(5L, ConversationEntry.EntryType.TOOL_RESULT, "result-pipecat", "attempt-2"),
                toolEvent(6L, ConversationEntry.EntryType.TOOL_CONTINUATION, "continuation-pipecat", "attempt-2"));
        when(toolCallDAO.findByChat(chat)).thenReturn(List.of(providerCall, pipecatCall));
        when(conversationEntryDAO.findReplayEntries(chat)).thenReturn(entries);

        var replay = service.runtimeReplay(chat);

        assertThat(replay).hasSize(6);
        assertThat(replay.get(0).getSequenceNumber()).isEqualTo(1L);
        assertThat(replay.get(0).getEventType()).isEqualTo("TOOL_REQUEST");
        assertThat(replay.get(0).getRuntime()).isEqualTo("provider-runtime");
        assertThat(replay.get(0).getArguments()).isEqualTo("{\"private\":\"provider-arguments\"}");
        assertThat(replay.get(0).getExactResult()).isNull();
        assertThat(replay.get(1).getSequenceNumber()).isEqualTo(2L);
        assertThat(replay.get(1).getEventType()).isEqualTo("TOOL_DECISION");
        assertThat(replay.get(2).getSequenceNumber()).isEqualTo(3L);
        assertThat(replay.get(2).getEventType()).isEqualTo("TOOL_REQUEST");
        assertThat(replay.get(2).getRuntime()).isEqualTo("pipecat");
        assertThat(replay.get(2).getArguments()).isEqualTo("{\"private\":\"pipecat-arguments\"}");
        assertThat(replay.get(3).getSequenceNumber()).isEqualTo(4L);
        assertThat(replay.get(3).getEventType()).isEqualTo("TOOL_EXECUTION");
        assertThat(replay.get(4).getSequenceNumber()).isEqualTo(5L);
        assertThat(replay.get(4).getEventType()).isEqualTo("TOOL_RESULT");
        assertThat(replay.get(4).getExactResult()).isEqualTo("{\"private\":\"pipecat-result\"}");
        assertThat(replay.get(5).getSequenceNumber()).isEqualTo(6L);
        assertThat(replay.get(5).getEventType()).isEqualTo("TOOL_CONTINUATION");
        assertThat(replay.get(5).getRuntime()).isEqualTo("pipecat");
        var inOrder = inOrder(chatDAO, toolCallDAO, conversationEntryDAO);
        inOrder.verify(chatDAO).lock("chat-1");
        inOrder.verify(toolCallDAO).findByChat(chat);
        inOrder.verify(conversationEntryDAO).findReplayEntries(chat);
    }

    @Test
    void continuationIsAppendedAfterTerminalResultAndRetriesAreIdempotent() {
        ToolCall call = pendingAttempt();
        when(toolCallDAO.findByChatAndAttemptId(chat, "attempt-1")).thenReturn(Optional.of(call));
        Map<String, ConversationEntry> entries = new HashMap<>();
        when(conversationEntryDAO.findByChatAndIdempotencyKey(any(), anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(entries.get(invocation.getArgument(1))));
        when(conversationEntryDAO.create(any(ConversationEntry.class))).thenAnswer(invocation -> {
            ConversationEntry created = invocation.getArgument(0);
            entries.put(created.getIdempotencyKey(), created);
            return created;
        });
        ToolCallContinuationDTO continuation = new ToolCallContinuationDTO();
        continuation.setIdempotencyKey("continuation-1");
        continuation.setResultAttemptId("attempt-1");
        continuation.setRuntime("pipecat");
        continuation.setContent("Continue with the search results");

        assertThrows(IdempotencyConflictException.class,
                () -> service.appendContinuation(chat, "attempt-1", continuation));
        call.setExecutionState(ToolCall.ExecutionState.SUCCEEDED);
        service.appendContinuation(chat, "attempt-1", continuation);
        service.appendContinuation(chat, "attempt-1", continuation);

        assertThat(entries).hasSize(1);
        ConversationEntry entry = entries.values().iterator().next();
        assertThat(entry.getType()).isEqualTo(ConversationEntry.EntryType.TOOL_CONTINUATION);
        assertThat(entry.getCausalReference()).isEqualTo("attempt-1");
        assertThat(entry.getText()).isEqualTo("Continue with the search results");
        assertThat(entry.getEventSnapshot()).contains("\"runtime\":\"pipecat\"");
        verify(conversationEntryDAO).create(any(ConversationEntry.class));

        continuation.setContent("Changed continuation");
        assertThrows(IdempotencyConflictException.class,
                () -> service.appendContinuation(chat, "attempt-1", continuation));
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

    private ConversationEntry toolEvent(long sequence, ConversationEntry.EntryType type, String key,
            String attemptId) {
        ConversationEntry entry = new ConversationEntry();
        entry.setSequence(sequence);
        entry.setType(type);
        entry.setIdempotencyKey(key);
        entry.setCausalReference(attemptId);
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
