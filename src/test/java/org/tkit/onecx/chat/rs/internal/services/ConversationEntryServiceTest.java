package org.tkit.onecx.chat.rs.internal.services;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.tkit.onecx.chat.domain.daos.ChatDAO;
import org.tkit.onecx.chat.domain.models.ConversationEntry;
import org.tkit.quarkus.test.WithDBData;

import gen.org.tkit.onecx.chat.rs.internal.model.ConversationEntryStatusDTO;
import gen.org.tkit.onecx.chat.rs.internal.model.ConversationEntryTypeDTO;
import gen.org.tkit.onecx.chat.rs.internal.model.CreateOrUpdateConversationEntryDTO;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@WithDBData(value = "data/testdata-internal.xml", deleteBeforeInsert = true, deleteAfterTest = true, rinseAndRepeat = true)
class ConversationEntryServiceTest {

    @Inject
    ConversationEntryService service;

    @Inject
    ChatDAO chatDao;

    private static CreateOrUpdateConversationEntryDTO dtoWithText(String idempotencyKey, String text) {
        var dto = new CreateOrUpdateConversationEntryDTO();
        dto.setIdempotencyKey(idempotencyKey);
        dto.setEntryType(ConversationEntryTypeDTO.HUMAN_UTTERANCE);
        dto.setText(text);
        return dto;
    }

    @Test
    void firstWriteAllocatesNextSequenceNumber() {
        var chat = chatDao.findById("chat-22-222");
        var dto = dtoWithText("new-key-1", "Hello");

        var outcome = service.createOrUpdate(chat, dto);

        assertThat(outcome.isCreated()).isTrue();
        assertThat(outcome.getEntry().getSequenceNumber()).isEqualTo(3L);
    }

    @Test
    void identicalRetryIsNoOp() {
        var chat = chatDao.findById("chat-22-222");

        var first = service.createOrUpdate(chat, dtoWithText("idem-repeat-1", "Same text"));
        var second = service.createOrUpdate(chat, dtoWithText("idem-repeat-1", "Same text"));

        assertThat(second.isCreated()).isFalse();
        assertThat(second.getEntry().getSequenceNumber()).isEqualTo(first.getEntry().getSequenceNumber());
    }

    @Test
    void monotonicGrowthUpdatesTextAndKeepsSequence() {
        var chat = chatDao.findById("chat-22-222");

        var first = service.createOrUpdate(chat, dtoWithText("idem-grow-1", "Hel"));
        var sequenceNumber = first.getEntry().getSequenceNumber();

        var second = service.createOrUpdate(chat, dtoWithText("idem-grow-1", "Hello world"));

        assertThat(second.getEntry().getText()).isEqualTo("Hello world");
        assertThat(second.getEntry().getSequenceNumber()).isEqualTo(sequenceNumber);
    }

    @Test
    void incompatibleRetryThrowsConflict() {
        var chat = chatDao.findById("chat-22-222");

        service.createOrUpdate(chat, dtoWithText("idem-conflict-1", "Hello"));

        Assertions.assertThrows(ConversationEntryService.ConversationEntryConflictException.class,
                () -> service.createOrUpdate(chat, dtoWithText("idem-conflict-1", "Goodbye")));
    }

    @Test
    void terminalStatusIsImmutableAfterFinalization() {
        var chat = chatDao.findById("chat-22-222");

        var dto = dtoWithText("idem-terminal-1", "Done");
        dto.setStatus(ConversationEntryStatusDTO.COMPLETED);
        service.createOrUpdate(chat, dto);

        Assertions.assertThrows(ConversationEntryService.ConversationEntryConflictException.class,
                () -> service.createOrUpdate(chat, dtoWithText("idem-terminal-1", "Done")));
    }

    @Test
    void finalizingWithoutResendingTextKeepsExistingText() {
        var chat = chatDao.findById("chat-22-222");

        service.createOrUpdate(chat, dtoWithText("idem-finalize-1", "Hello world"));

        var finalizeDto = new CreateOrUpdateConversationEntryDTO();
        finalizeDto.setIdempotencyKey("idem-finalize-1");
        finalizeDto.setEntryType(ConversationEntryTypeDTO.HUMAN_UTTERANCE);
        finalizeDto.setStatus(ConversationEntryStatusDTO.COMPLETED);

        var outcome = service.createOrUpdate(chat, finalizeDto);

        assertThat(outcome.getEntry().getText()).isEqualTo("Hello world");
        assertThat(outcome.getEntry().getStatus()).isEqualTo(ConversationEntry.Status.COMPLETED);
    }

    @Test
    void replayReturnsOnlyTerminalInSequenceOrder() {
        var chat = chatDao.findById("chat-22-222");

        var result = service.replay(chat);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo("entry-11-111");
    }
}
