package org.tkit.onecx.chat.domain.daos;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.tkit.quarkus.test.WithDBData;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@WithDBData(value = "data/testdata-internal.xml", deleteBeforeInsert = true, deleteAfterTest = true, rinseAndRepeat = true)
class ConversationEntryDAOPersistenceTest {

    @Inject
    ConversationEntryDAO dao;

    @Test
    void findVisibleTerminalEntriesForChatExcludesInProgressAndOrdersBySequence() {
        var result = dao.findVisibleTerminalEntriesForChat("chat-22-222");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo("entry-11-111");
        assertThat(result.get(0).getSequenceNumber()).isEqualTo(1L);
    }

    @Test
    void findMaxSequenceNumberForChatReturnsHighestSequence() {
        var result = dao.findMaxSequenceNumberForChat("chat-22-222");
        assertThat(result).isEqualTo(2L);
    }

    @Test
    void findMaxSequenceNumberForChatReturnsZeroWhenNoEntries() {
        var result = dao.findMaxSequenceNumberForChat("chat-11-111");
        assertThat(result).isEqualTo(0L);
    }

    @Test
    void findByChatIdAndIdempotencyKeyReturnsMatch() {
        var result = dao.findByChatIdAndIdempotencyKey("chat-22-222", "idem-11-111");
        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo("entry-11-111");
    }
}
