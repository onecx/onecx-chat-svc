package org.tkit.onecx.chat.domain.models;

import static jakarta.persistence.FetchType.LAZY;

import jakarta.persistence.*;

import org.hibernate.annotations.TenantId;
import org.tkit.quarkus.jpa.models.TraceableEntity;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "CONVERSATION_ENTRY")
public class ConversationEntry extends TraceableEntity {

    @TenantId
    @Column(name = "TENANT_ID")
    private String tenantId;

    @Column(name = "IDEMPOTENCY_KEY", length = 255, nullable = false)
    private String idempotencyKey;

    @Column(name = "SEQUENCE_NUMBER", nullable = false)
    private Long sequenceNumber;

    @Column(name = "ENTRY_TYPE")
    @Enumerated(EnumType.STRING)
    private EntryType entryType;

    @Column(name = "TEXT", length = 10000)
    private String text;

    @Column(name = "STATUS")
    @Enumerated(EnumType.STRING)
    private Status status = Status.IN_PROGRESS;

    @Column(name = "USER_ID")
    private String userId;

    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "CHAT_ID")
    private Chat chat;

    public enum EntryType {
        HUMAN_UTTERANCE,
        ASSISTANT_CHECKPOINT,
        TERMINAL_OUTCOME
    }

    public enum Status {
        IN_PROGRESS,
        COMPLETED,
        INTERRUPTED
    }

}
