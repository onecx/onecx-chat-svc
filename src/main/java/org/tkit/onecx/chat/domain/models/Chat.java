package org.tkit.onecx.chat.domain.models;

import static jakarta.persistence.CascadeType.*;
import static jakarta.persistence.FetchType.LAZY;

import java.util.HashSet;
import java.util.Set;

import jakarta.persistence.*;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.TenantId;
import org.tkit.quarkus.jpa.models.TraceableEntity;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "CHAT")
@NamedEntityGraph(name = Chat.CHAT_LOAD, includeAllAttributes = true)
@SuppressWarnings("java:S2160")
public class Chat extends TraceableEntity {

    public static final String CHAT_LOAD = "CHAT_LOAD";

    @Column(name = "TYPE")
    @Enumerated(EnumType.STRING)
    private ChatType type;

    @TenantId
    @Column(name = "TENANT_ID")
    private String tenantId;

    @Column(name = "TOPIC")
    private String topic;

    @Column(name = "SUMMARY", columnDefinition = "varchar(1000)")
    private String summary;

    @Column(name = "APP_ID")
    private String appId;

    @OneToMany(cascade = ALL, fetch = LAZY, mappedBy = "chat", orphanRemoval = true)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @OrderBy("creationDate ASC")
    private Set<Message> messages = new HashSet<>();

    @ManyToMany(cascade = { PERSIST, MERGE }, fetch = LAZY)
    @JoinTable(name = "CHAT_PARTICIPANT", joinColumns = @JoinColumn(name = "CHAT_GUID"), inverseJoinColumns = @JoinColumn(name = "PARTICIPANT_GUID", referencedColumnName = "guid"))
    @OrderBy("creationDate ASC")
    private Set<Participant> participants = new HashSet<>();

    @Column(name = "USER_ID")
    private String userId;

    public enum ChatType {
        HUMAN_DIRECT_CHAT,
        HUMAN_GROUP_CHAT,
        AI_CHAT
    }

}
