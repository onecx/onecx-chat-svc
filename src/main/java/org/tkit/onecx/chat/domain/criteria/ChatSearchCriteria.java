package org.tkit.onecx.chat.domain.criteria;

import org.tkit.onecx.chat.domain.models.Chat.ChatType;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@RegisterForReflection
public class ChatSearchCriteria {

    private ChatType type;

    private String topic;

    private String appId;

    private String userId;

    private String participant;

    private Integer pageNumber;

    private Integer pageSize;

}
