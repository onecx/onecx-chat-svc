package org.tkit.onecx.chat.rs.internal.services;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.tkit.onecx.chat.domain.daos.ChatDAO;
import org.tkit.onecx.chat.domain.daos.ConversationEntryDAO;
import org.tkit.onecx.chat.domain.daos.ToolCallDAO;
import org.tkit.onecx.chat.domain.models.Chat;
import org.tkit.onecx.chat.domain.models.ConversationEntry;
import org.tkit.onecx.chat.domain.models.ToolCall;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import gen.org.tkit.onecx.chat.rs.internal.model.RegisterToolCallDTO;
import gen.org.tkit.onecx.chat.rs.internal.model.ToolCallBlockedReasonDTO;
import gen.org.tkit.onecx.chat.rs.internal.model.ToolCallClaimDTO;
import gen.org.tkit.onecx.chat.rs.internal.model.ToolCallClaimResponseDTO;
import gen.org.tkit.onecx.chat.rs.internal.model.ToolCallContinuationDTO;
import gen.org.tkit.onecx.chat.rs.internal.model.ToolCallDecisionDTO;
import gen.org.tkit.onecx.chat.rs.internal.model.ToolCallDecisionStateDTO;
import gen.org.tkit.onecx.chat.rs.internal.model.ToolCallExecutionStateDTO;
import gen.org.tkit.onecx.chat.rs.internal.model.ToolCallOutcomeDTO;
import gen.org.tkit.onecx.chat.rs.internal.model.ToolCallPolicyDecisionDTO;
import gen.org.tkit.onecx.chat.rs.internal.model.ToolCallRuntimeReplayDTO;
import gen.org.tkit.onecx.chat.rs.internal.model.ToolCallSafeSummaryDTO;
import io.quarkus.security.identity.SecurityIdentity;

@ApplicationScoped
@Transactional(Transactional.TxType.NOT_SUPPORTED)
public class ToolCallService {

    @Inject
    ToolCallDAO dao;

    @Inject
    ChatDAO chatDAO;

    @Inject
    ConversationEntryDAO conversationEntryDAO;

    @Inject
    SecurityIdentity identity;

    @Inject
    ObjectMapper objectMapper;

    @Transactional
    public ToolCallSafeSummaryDTO register(Chat chat, RegisterToolCallDTO request) {
        Chat lockedChat = lock(chat);
        ToolCall.PolicyDecision policy = ToolCall.PolicyDecision.valueOf(request.getPolicyDecision().name());
        var existing = dao.findByChatAndIdempotencyKey(lockedChat, request.getIdempotencyKey());
        if (existing.isPresent()) {
            ToolCall call = existing.get();
            validateSameRequest(call, request, policy);
            return safeSummary(call);
        }
        if (dao.findByChatAndAttemptId(lockedChat, request.getAttemptId()).isPresent()) {
            throw conflict("Attempt ID is already bound to another request.");
        }

        ToolCall call = new ToolCall();
        call.setChat(lockedChat);
        call.setAttemptId(request.getAttemptId());
        call.setIdempotencyKey(request.getIdempotencyKey());
        call.setToolName(request.getToolName());
        call.setArguments(request.getArguments());
        call.setConfigurationSnapshotHash(request.getConfigurationSnapshotHash());
        call.setSchemaHash(request.getSchemaHash());
        call.setArgumentHash(request.getArgumentHash());
        call.setProposingPrincipal(request.getProposingPrincipal());
        call.setReportingPrincipal(identity.getPrincipal().getName());
        call.setRuntime(request.getRuntime());
        call.setPolicyDecision(policy);
        call.setDecisionState(policy == ToolCall.PolicyDecision.ALWAYS_ASK
                ? ToolCall.DecisionState.PENDING
                : ToolCall.DecisionState.NOT_REQUIRED);
        call.setExecutionState(policy == ToolCall.PolicyDecision.DENY
                ? ToolCall.ExecutionState.BLOCKED
                : ToolCall.ExecutionState.NOT_STARTED);
        call.setSafeSummary(request.getSafeSummary());
        call.setRequestSafeSummary(request.getSafeSummary());
        if (policy == ToolCall.PolicyDecision.DENY) {
            call.setBlockedReason("POLICY_DENIED");
        }
        call.setSequence(conversationEntryDAO.findMaxSequenceByChat(lockedChat) + 1);
        call = dao.create(call);
        appendEvent(lockedChat, eventKey(call, "request"), ConversationEntry.EntryType.TOOL_REQUEST,
                call.getSafeSummary(), call.getAttemptId(), eventSnapshot(call));
        if (policy == ToolCall.PolicyDecision.DENY) {
            appendEvent(lockedChat, eventKey(call, "denied"), ConversationEntry.EntryType.TOOL_RESULT,
                    call.getSafeSummary(), call.getAttemptId(), eventSnapshot(call));
        }
        return safeSummary(call);
    }

    @Transactional
    public ToolCallSafeSummaryDTO decide(Chat chat, String attemptId, ToolCallDecisionDTO request) {
        Chat lockedChat = lock(chat);
        ToolCall call = findAttempt(lockedChat, attemptId);
        ToolCall.DecisionState decision = ToolCall.DecisionState.valueOf(request.getDecision().name());
        if (call.getDecisionIdempotencyKey() != null) {
            if (Objects.equals(call.getDecisionIdempotencyKey(), request.getIdempotencyKey())
                    && call.getDecisionState() == decision) {
                return safeSummary(call);
            }
            throw conflict("A different terminal decision has already been recorded.");
        }
        if (call.getDecisionState() != ToolCall.DecisionState.PENDING) {
            throw conflict("This tool call does not have a pending proposal.");
        }
        call.setDecisionState(decision);
        call.setDecisionIdempotencyKey(request.getIdempotencyKey());
        call.setDecisionPrincipal(identity.getPrincipal().getName());
        if (decision == ToolCall.DecisionState.REJECTED) {
            call.setExecutionState(ToolCall.ExecutionState.BLOCKED);
            call.setBlockedReason("PROPOSAL_REJECTED");
        }
        appendEvent(lockedChat, eventKey(call, "decision"), ConversationEntry.EntryType.TOOL_DECISION,
                decision.name(), attemptId, eventSnapshot(call));
        if (decision == ToolCall.DecisionState.REJECTED) {
            appendEvent(lockedChat, eventKey(call, "rejected"), ConversationEntry.EntryType.TOOL_RESULT,
                    "Tool call proposal rejected", attemptId, eventSnapshot(call));
        }
        return safeSummary(dao.update(call));
    }

    @Transactional
    public ToolCallClaimResponseDTO claim(Chat chat, String attemptId, ToolCallClaimDTO request) {
        Chat lockedChat = lock(chat);
        ToolCall call = findAttempt(lockedChat, attemptId);
        if (call.getClaimId() != null) {
            if (Objects.equals(call.getClaimIdempotencyKey(), request.getIdempotencyKey())
                    && Objects.equals(call.getClaimOwner(), identity.getPrincipal().getName())) {
                return claimResponse(call, false);
            }
            throw conflict("This tool call has already been claimed.");
        }
        if (call.getPolicyDecision() == ToolCall.PolicyDecision.DENY
                || call.getDecisionState() == ToolCall.DecisionState.PENDING
                || call.getDecisionState() == ToolCall.DecisionState.REJECTED
                || (call.getPolicyDecision() == ToolCall.PolicyDecision.ALWAYS_ASK
                        && call.getDecisionState() != ToolCall.DecisionState.APPROVED)) {
            throw conflict("This tool call is not approved for execution.");
        }
        if (call.getExecutionState() != ToolCall.ExecutionState.NOT_STARTED) {
            throw conflict("This tool call is not available for execution.");
        }
        if (dao.findActiveClaimByChat(lockedChat).isPresent()) {
            throw conflict("Another tool call is executing in this conversation.");
        }

        call.setClaimId(eventKey(call, "claim"));
        call.setClaimIdempotencyKey(request.getIdempotencyKey());
        call.setClaimOwner(identity.getPrincipal().getName());
        call.setExecutionState(ToolCall.ExecutionState.CLAIMED);
        dao.update(call);
        appendEvent(lockedChat, eventKey(call, "claim-event"), ConversationEntry.EntryType.TOOL_EXECUTION,
                "Execution claimed", attemptId, eventSnapshot(call));
        return claimResponse(call, true);
    }

    @Transactional
    public ToolCallSafeSummaryDTO reportOutcome(Chat chat, String attemptId, ToolCallOutcomeDTO request) {
        Chat lockedChat = lock(chat);
        ToolCall call = findAttempt(lockedChat, attemptId);
        if (call.getOutcomeIdempotencyKey() != null) {
            if (Objects.equals(call.getClaimOwner(), identity.getPrincipal().getName())
                    && Objects.equals(call.getOutcomeIdempotencyKey(), request.getIdempotencyKey())
                    && Objects.equals(call.getClaimId(), request.getClaimId())
                    && Objects.equals(call.getExactResult(), request.getExactResult())
                    && Objects.equals(call.getSafeSummary(), request.getSafeSummary())
                    && call.getExecutionState().name().equals(request.getOutcome().name())) {
                return safeSummary(call);
            }
            throw conflict("A different execution outcome has already been recorded.");
        }
        if (call.getExecutionState() != ToolCall.ExecutionState.CLAIMED
                || !Objects.equals(call.getClaimId(), request.getClaimId())
                || !Objects.equals(call.getClaimOwner(), identity.getPrincipal().getName())) {
            throw conflict("Only the owner of the active claim may report its outcome.");
        }
        call.setExecutionState(ToolCall.ExecutionState.valueOf(request.getOutcome().name()));
        call.setOutcomeIdempotencyKey(request.getIdempotencyKey());
        call.setOutcomePrincipal(identity.getPrincipal().getName());
        call.setSafeSummary(request.getSafeSummary());
        call.setExactResult(request.getExactResult());
        call = dao.update(call);
        appendEvent(lockedChat, eventKey(call, "result"), ConversationEntry.EntryType.TOOL_RESULT,
                request.getSafeSummary(), attemptId, eventSnapshot(call));
        return safeSummary(call);
    }

    @Transactional
    public void appendContinuation(Chat chat, String attemptId, ToolCallContinuationDTO request) {
        Chat lockedChat = lock(chat);
        ToolCall call = findAttempt(lockedChat, attemptId);
        if (!Objects.equals(request.getResultAttemptId(), attemptId)
                || call.getExecutionState() != ToolCall.ExecutionState.SUCCEEDED
                        && call.getExecutionState() != ToolCall.ExecutionState.FAILED
                        && call.getExecutionState() != ToolCall.ExecutionState.BLOCKED) {
            throw conflict("A continuation must follow a terminal result in the same conversation.");
        }
        String continuationKey = eventKey(call, "continuation:" + UUID.nameUUIDFromBytes(
                request.getIdempotencyKey().getBytes(StandardCharsets.UTF_8)));
        appendEvent(lockedChat, continuationKey, ConversationEntry.EntryType.TOOL_CONTINUATION,
                request.getContent(), attemptId, json(Map.of("runtime", request.getRuntime())));
    }

    public List<ToolCallSafeSummaryDTO> history(Chat chat) {
        return dao.findByChat(chat).stream().map(this::safeSummary).toList();
    }

    @Transactional
    public List<ToolCallRuntimeReplayDTO> runtimeReplay(Chat chat) {
        Chat lockedChat = lock(chat);
        List<ToolCall> calls = dao.findByChat(lockedChat);
        List<ConversationEntry> entries = conversationEntryDAO.findReplayEntries(lockedChat);
        List<ToolCallRuntimeReplayDTO> replay = new ArrayList<>();
        for (ConversationEntry entry : entries) {
            ToolCallRuntimeReplayDTO event = new ToolCallRuntimeReplayDTO();
            event.setSequenceNumber(entry.getSequence());
            event.setEventIdempotencyKey(entry.getIdempotencyKey());
            event.setEventType(entry.getType().name());
            event.setCausalReference(entry.getCausalReference());
            event.setSafeSummary(entry.getText());
            event.setEventSnapshot(entry.getEventSnapshot());
            if (entry.getType() == ConversationEntry.EntryType.HUMAN
                    || entry.getType() == ConversationEntry.EntryType.ASSISTANT) {
                replay.add(event);
                continue;
            }
            ToolCall call = calls.stream()
                    .filter(toolCall -> Objects.equals(toolCall.getAttemptId(), entry.getCausalReference()))
                    .findFirst()
                    .orElseThrow(() -> conflict("A tool event has no matching attempt."));
            event.setAttemptId(call.getAttemptId());
            event.setToolName(call.getToolName());
            event.setRuntime(call.getRuntime());
            if (entry.getType() == ConversationEntry.EntryType.TOOL_REQUEST) {
                event.setArguments(call.getArguments());
                event.setConfigurationSnapshotHash(call.getConfigurationSnapshotHash());
                event.setSchemaHash(call.getSchemaHash());
                event.setArgumentHash(call.getArgumentHash());
            }
            if (entry.getType() == ConversationEntry.EntryType.TOOL_RESULT) {
                event.setExactResult(call.getExactResult());
            }
            replay.add(event);
        }
        return replay;
    }

    private Chat lock(Chat chat) {
        Chat locked = chatDAO.lock(chat.getId());
        if (locked == null) {
            throw new IllegalStateException("Chat not found.");
        }
        return locked;
    }

    private ToolCall findAttempt(Chat chat, String attemptId) {
        return dao.findByChatAndAttemptId(chat, attemptId)
                .orElseThrow(() -> new ToolCallNotFoundException("Tool call attempt not found."));
    }

    private void appendEvent(Chat chat, String key, ConversationEntry.EntryType type, String text,
            String causalReference, String eventSnapshot) {
        var existing = conversationEntryDAO.findByChatAndIdempotencyKey(chat, key);
        if (existing.isPresent()) {
            ConversationEntry entry = existing.get();
            if (entry.getType() != type || !Objects.equals(entry.getText(), text)
                    || !Objects.equals(entry.getCausalReference(), causalReference)
                    || !Objects.equals(entry.getEventSnapshot(), eventSnapshot)) {
                throw conflict("An event idempotency key is already bound to different event data.");
            }
            return;
        }
        ConversationEntry entry = new ConversationEntry();
        entry.setChat(chat);
        entry.setIdempotencyKey(key);
        entry.setSequence(conversationEntryDAO.findMaxSequenceByChat(chat) + 1);
        entry.setType(type);
        entry.setStatus(ConversationEntry.EntryStatus.COMPLETED);
        entry.setText(text);
        entry.setCausalReference(causalReference);
        entry.setEventSnapshot(eventSnapshot);
        conversationEntryDAO.create(entry);
    }

    private String eventSnapshot(ToolCall call) {
        Map<String, String> snapshot = new TreeMap<>();
        snapshot.put("policyDecision", call.getPolicyDecision().name());
        snapshot.put("decisionState", call.getDecisionState().name());
        snapshot.put("executionState", call.getExecutionState().name());
        snapshot.put("runtime", call.getRuntime());
        snapshot.put("toolName", call.getToolName());
        if (call.getBlockedReason() != null) {
            snapshot.put("blockedReason", call.getBlockedReason());
        }
        return json(snapshot);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize a tool lifecycle event snapshot.", ex);
        }
    }

    private String eventKey(ToolCall call, String suffix) {
        return "tool:" + call.getId() + ":" + suffix;
    }

    private void validateSameRequest(ToolCall call, RegisterToolCallDTO request, ToolCall.PolicyDecision policy) {
        if (!Objects.equals(call.getAttemptId(), request.getAttemptId())
                || !Objects.equals(call.getToolName(), request.getToolName())
                || !Objects.equals(call.getArguments(), request.getArguments())
                || !Objects.equals(call.getConfigurationSnapshotHash(), request.getConfigurationSnapshotHash())
                || !Objects.equals(call.getSchemaHash(), request.getSchemaHash())
                || !Objects.equals(call.getArgumentHash(), request.getArgumentHash())
                || !Objects.equals(call.getProposingPrincipal(), request.getProposingPrincipal())
                || !Objects.equals(call.getRuntime(), request.getRuntime())
                || !Objects.equals(call.getRequestSafeSummary(), request.getSafeSummary())
                || call.getPolicyDecision() != policy) {
            throw conflict("The idempotency key is already bound to different tool-call data.");
        }
    }

    private ToolCallSafeSummaryDTO safeSummary(ToolCall call) {
        ToolCallSafeSummaryDTO dto = new ToolCallSafeSummaryDTO();
        dto.setAttemptId(call.getAttemptId());
        dto.setToolName(call.getToolName());
        dto.setSafeSummary(call.getSafeSummary());
        dto.setPolicyDecision(ToolCallPolicyDecisionDTO.valueOf(call.getPolicyDecision().name()));
        dto.setDecisionState(ToolCallDecisionStateDTO.valueOf(call.getDecisionState().name()));
        dto.setExecutionState(ToolCallExecutionStateDTO.valueOf(call.getExecutionState().name()));
        dto.setBlockedReason(call.getBlockedReason() == null
                ? null
                : ToolCallBlockedReasonDTO.valueOf(call.getBlockedReason()));
        dto.setSequenceNumber(call.getSequence());
        dto.setRuntime(call.getRuntime());
        return dto;
    }

    private ToolCallClaimResponseDTO claimResponse(ToolCall call, boolean newlyGranted) {
        ToolCallClaimResponseDTO response = new ToolCallClaimResponseDTO();
        response.setAttemptId(call.getAttemptId());
        response.setClaimId(call.getClaimId());
        response.setOwner(call.getClaimOwner());
        response.setArguments(call.getArguments());
        response.setNewlyGranted(newlyGranted);
        return response;
    }

    private IdempotencyConflictException conflict(String message) {
        return new IdempotencyConflictException(message);
    }
}
