package org.tkit.onecx.chat.domain.models;

import static jakarta.persistence.FetchType.LAZY;

import java.util.HashSet;
import java.util.Set;

import jakarta.persistence.*;

import org.hibernate.annotations.TenantId;
import org.tkit.quarkus.jpa.models.TraceableEntity;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "PARTICIPANT")
@NamedEntityGraph(name = "Participant.withChats", attributeNodes = {
        @NamedAttributeNode(value = "chats", subgraph = "chatGraph")
}, subgraphs = {
        @NamedSubgraph(name = "chatGraph", attributeNodes = {
                @NamedAttributeNode("id"),
                @NamedAttributeNode("topic"),
                @NamedAttributeNode("type")
        })
})
public class Participant extends TraceableEntity {

    @TenantId
    @Column(name = "TENANT_ID")
    private String tenantId;

    @Column(name = "USER_ID")
    private String userId;

    @Column(name = "EMAIL")
    private String email;

    @Column(name = "TYPE")
    @Enumerated(EnumType.STRING)
    private ParticipantType type;

    @Column(name = "USER_NAME")
    private String userName;

    @ManyToMany(mappedBy = "participants", fetch = LAZY)
    private Set<Chat> chats = new HashSet<>();

    public enum ParticipantType {
        HUMAN,
        ASSISTANT,
    }

}
