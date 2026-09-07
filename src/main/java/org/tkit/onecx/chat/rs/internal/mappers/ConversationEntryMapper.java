package org.tkit.onecx.chat.rs.internal.mappers;

import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.tkit.onecx.chat.domain.models.ConversationEntry;
import org.tkit.quarkus.rs.mappers.OffsetDateTimeMapper;

import gen.org.tkit.onecx.chat.rs.internal.model.ConversationEntryDTO;

@Mapper(uses = { OffsetDateTimeMapper.class })
public interface ConversationEntryMapper {

    @Mapping(target = "chatId", source = "chat.id")
    @Mapping(target = "version", source = "modificationCount")
    ConversationEntryDTO map(ConversationEntry entity);

    List<ConversationEntryDTO> mapList(List<ConversationEntry> entities);

}
