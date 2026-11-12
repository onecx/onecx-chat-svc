package org.tkit.onecx.chat.domain.models;

import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import org.hibernate.annotations.TenantId;
import org.tkit.quarkus.jpa.models.TraceableEntity;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "TOOL_CALL", uniqueConstraints = {
        @UniqueConstraint(name = "UK_TOOL_CALL_CHAT_ATTEMPT", columnNames = { "CHAT_ID", "ATTEMPT_ID" }),
        @UniqueConstraint(name = "UK_TOOL_CALL_CHAT_IDEMP", columnNames = { "CHAT_ID", "IDEMPOTENCY_KEY" }),
        @UniqueConstraint(name = "UK_TOOL_CALL_CHAT_SEQ", columnNames = { "CHAT_ID", "SEQUENCE_NO" }),
        @UniqueConstraint(name = "UK_TOOL_CALL_CHAT_CLAIM", columnNames = { "CHAT_ID", "CLAIM_ID" })
})
public class ToolCall extends TraceableEntity {

    @TenantId
    @Column(name = "TENANT_ID", nullable = false, updatable = false)
    private String tenantId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "CHAT_ID", nullable = false, updatable = false)
    private Chat chat;

    @Column(name = "ATTEMPT_ID", nullable = false, updatable = false)
    private String attemptId;

    @Column(name = "IDEMPOTENCY_KEY", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "TOOL_NAME", nullable = false, updatable = false)
    private String toolName;

    @Column(name = "ARGUMENTS", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String arguments;

    @Column(name = "CONFIGURATION_HASH", nullable = false, updatable = false)
    private String configurationSnapshotHash;

    @Column(name = "SCHEMA_HASH", nullable = false, updatable = false)
    private String schemaHash;

    @Column(name = "ARGUMENT_HASH", nullable = false, updatable = false)
    private String argumentHash;

    @Column(name = "PROPOSING_PRINCIPAL", nullable = false, updatable = false)
    private String proposingPrincipal;

    @Column(name = "REPORTING_PRINCIPAL", nullable = false, updatable = false)
    private String reportingPrincipal;

    @Column(name = "DECISION_PRINCIPAL")
    private String decisionPrincipal;

    @Column(name = "RUNTIME", nullable = false, updatable = false)
    private String runtime;

    @Enumerated(EnumType.STRING)
    @Column(name = "POLICY_DECISION", nullable = false, updatable = false)
    private PolicyDecision policyDecision;

    @Enumerated(EnumType.STRING)
    @Column(name = "DECISION_STATE", nullable = false)
    private DecisionState decisionState;

    @Enumerated(EnumType.STRING)
    @Column(name = "EXECUTION_STATE", nullable = false)
    private ExecutionState executionState;

    @Column(name = "SAFE_SUMMARY", columnDefinition = "TEXT")
    private String safeSummary;

    @Column(name = "CLAIM_ID")
    private String claimId;

    @Column(name = "CLAIM_IDEMPOTENCY_KEY")
    private String claimIdempotencyKey;

    @Column(name = "CLAIM_OWNER")
    private String claimOwner;

    @Column(name = "OUTCOME_IDEMPOTENCY_KEY")
    private String outcomeIdempotencyKey;

    @Column(name = "OUTCOME_PRINCIPAL")
    private String outcomePrincipal;

    @Column(name = "EXACT_RESULT", columnDefinition = "TEXT")
    private String exactResult;

    @Column(name = "BLOCKED_REASON")
    private String blockedReason;

    @Column(name = "SEQUENCE_NO", nullable = false, updatable = false)
    private Long sequence;

    @Column(name = "DECISION_IDEMPOTENCY_KEY")
    private String decisionIdempotencyKey;

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ToolCall that))
            return false;
        if (!super.equals(o))
            return false;
        return Objects.equals(tenantId, that.tenantId) && Objects.equals(chat, that.chat)
                && Objects.equals(attemptId, that.attemptId) && Objects.equals(idempotencyKey, that.idempotencyKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), tenantId, chat, attemptId, idempotencyKey);
    }

    public enum PolicyDecision {
        DENY,
        ALLOW,
        ALWAYS_ASK
    }

    public enum DecisionState {
        NOT_REQUIRED,
        PENDING,
        APPROVED,
        REJECTED
    }

    public enum ExecutionState {
        NOT_STARTED,
        CLAIMED,
        SUCCEEDED,
        FAILED,
        BLOCKED
    }
}
