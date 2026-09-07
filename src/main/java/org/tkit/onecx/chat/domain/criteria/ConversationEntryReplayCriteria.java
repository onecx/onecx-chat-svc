package org.tkit.onecx.chat.domain.criteria;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@RegisterForReflection
public class ConversationEntryReplayCriteria {

    private String chatId;

}
