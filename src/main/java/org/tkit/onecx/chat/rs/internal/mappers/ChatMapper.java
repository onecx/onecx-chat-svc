package org.tkit.onecx.chat.rs.internal.mappers;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.mapstruct.IterableMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.MappingTarget;
import org.mapstruct.Named;
import org.mapstruct.ValueMapping;
import org.tkit.onecx.chat.domain.criteria.ChatSearchCriteria;
import org.tkit.onecx.chat.domain.models.Chat;
import org.tkit.onecx.chat.domain.models.Message;
import org.tkit.onecx.chat.domain.models.Message.MessageType;
import org.tkit.onecx.chat.domain.models.Participant;
import org.tkit.quarkus.jpa.daos.PageResult;
import org.tkit.quarkus.rs.mappers.OffsetDateTimeMapper;

import gen.io.github.onecx.ai.clients.model.ChatMessage;
import gen.io.github.onecx.ai.clients.model.ChatMessage.TypeEnum;
import gen.io.github.onecx.ai.clients.model.Conversation;
import gen.org.tkit.onecx.chat.rs.internal.model.*;

@Mapper(uses = { OffsetDateTimeMapper.class })
public interface ChatMapper {

    ChatSearchCriteria map(ChatSearchCriteriaDTO dto);

    @Mapping(target = "removeStreamItem", ignore = true)
    ChatPageResultDTO mapPage(PageResult<Chat> page);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "controlTraceabilityManual", ignore = true)
    @Mapping(target = "modificationCount", ignore = true)
    @Mapping(target = "persisted", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "messages", ignore = true)
    @Mapping(target = "userId", ignore = true)
    @Mapping(target = "participants", ignore = true)
    Chat create(CreateChatDTO object);

    List<Participant> mapParticipantDTOs(List<ParticipantDTO> participantDTOs);

    Set<Participant> mapParticipants(List<ParticipantDTO> participantDTOs);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "controlTraceabilityManual", ignore = true)
    @Mapping(target = "modificationCount", ignore = true)
    @Mapping(target = "persisted", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "chat", ignore = true)
    @Mapping(target = "userId", source = "userId")
    Message createMessage(CreateMessageDTO dto);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "controlTraceabilityManual", ignore = true)
    @Mapping(target = "modificationCount", ignore = true)
    @Mapping(target = "persisted", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "chats", ignore = true)
    Participant addParticipant(AddParticipantDTO dto);

    @IterableMapping(qualifiedByName = "mapSingleChat")
    List<ChatDTO> map(Stream<Chat> entity);

    @Mapping(target = "version", source = "modificationCount")
    @Mapping(target = "removeParticipantsItem", ignore = true)
    @Mapping(target = "participants", ignore = true)
    @Named("mapSingleChat")
    ChatDTO mapSingleChat(Chat chat);

    @Mapping(target = "version", source = "modificationCount")
    @Mapping(target = "removeParticipantsItem", ignore = true)
    ChatDTO mapChat(Chat chat);

    @Mapping(target = "version", source = "modificationCount")
    ParticipantDTO mapParticipant(Participant participant);

    List<ParticipantDTO> mapParticipantList(List<Participant> items);

    @Mapping(target = "version", source = "modificationCount")
    MessageDTO map(Message message);

    List<MessageDTO> mapMessageList(List<Message> items);

    @Mapping(target = "controlTraceabilityManual", ignore = true)
    @Mapping(target = "modificationCount", ignore = true)
    @Mapping(target = "persisted", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "chats", ignore = true)
    @Mapping(target = "id", ignore = true)
    Participant mapParticipant(ParticipantDTO participantDTO);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "creationDate", ignore = true)
    @Mapping(target = "creationUser", ignore = true)
    @Mapping(target = "modificationDate", ignore = true)
    @Mapping(target = "modificationUser", ignore = true)
    @Mapping(target = "controlTraceabilityManual", ignore = true)
    @Mapping(target = "modificationCount", ignore = true)
    @Mapping(target = "persisted", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "messages", ignore = true)
    @Mapping(target = "participants", ignore = true)
    @Mapping(target = "userId", ignore = true)
    void update(UpdateChatDTO chatDTO, @MappingTarget Chat entity);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "controlTraceabilityManual", ignore = true)
    @Mapping(target = "modificationCount", ignore = true)
    @Mapping(target = "persisted", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "messages", ignore = true)
    @Mapping(target = "userId", ignore = true)
    Chat map(UpdateChatDTO object);

    @Mapping(source = "id", target = "conversationId")
    @Mapping(source = "messages", target = "history")
    @Mapping(target = "conversationType", ignore = true)
    Conversation mapChat2Conversation(Chat object);

    @Mapping(source = "id", target = "conversationId")
    @Mapping(source = "text", target = "message")
    ChatMessage mapMessage(Message object);

    @ValueMapping(source = "HUMAN", target = "USER")
    TypeEnum mapMessage(MessageType object);

    static Long mapLocalDateTime(LocalDateTime time) {
        return time.toEpochSecond(ZoneOffset.UTC);
    }

    static LocalDateTime mapLongTime(Long timestamp) {
        return Instant.ofEpochSecond(timestamp).atZone(ZoneId.systemDefault()).toLocalDateTime();
    }

    @ValueMapping(source = "USER", target = "HUMAN")
    @ValueMapping(source = "ACTION", target = MappingConstants.NULL)
    @Mapping(target = "controlTraceabilityManual", ignore = true)
    @Mapping(target = "modificationCount", ignore = true)
    @Mapping(target = "creationUser", constant = "AiSvc")
    @Mapping(target = "modificationUser", constant = "AiSvc")
    @Mapping(target = "persisted", ignore = true)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "modificationDate", ignore = true)
    @Mapping(target = "chat", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "text", source = "message")
    @Mapping(target = "userId", ignore = true)
    Message mapAiSvcMessage(ChatMessage chatResponse);

}
