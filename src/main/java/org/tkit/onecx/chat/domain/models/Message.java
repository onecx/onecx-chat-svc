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
@Table(name = "MESSAGE")
public class Message extends TraceableEntity {

    @TenantId
    @Column(name = "TENANT_ID")
    private String tenantId;

    @Column(name = "TEXT", columnDefinition = "varchar(4000)")
    private String text;

    @Column(name = "TYPE")
    @Enumerated(EnumType.STRING)
    private MessageType type;

    @Column(name = "USER_ID")
    private String userId;

    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "CHAT_ID")
    private Chat chat;

    public enum MessageType {
        SYSTEM,
        HUMAN,
        ASSISTANT,
    }

}
