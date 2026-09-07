package org.tkit.onecx.chat.domain.daos;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mockito.Mockito;
import org.tkit.quarkus.jpa.exceptions.DAOException;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class ConversationEntryDAOTest {
    @Inject
    ConversationEntryDAO dao;

    @InjectMock
    EntityManager em;

    @BeforeEach
    void beforeAll() {
        Mockito.when(em.getCriteriaBuilder()).thenThrow(new RuntimeException("Test technical error exception"));
    }

    @Test
    void methodExceptionTests() {
        methodExceptionTests(() -> dao.findByChatIdAndIdempotencyKey("chatId", "key"),
                ConversationEntryDAO.ErrorKeys.ERROR_FIND_BY_CHAT_AND_IDEMPOTENCY_KEY);
        methodExceptionTests(() -> dao.findMaxSequenceNumberForChat("chatId"),
                ConversationEntryDAO.ErrorKeys.ERROR_FIND_MAX_SEQUENCE_NUMBER);
        methodExceptionTests(() -> dao.findVisibleTerminalEntriesForChat("chatId"),
                ConversationEntryDAO.ErrorKeys.ERROR_FIND_VISIBLE_TERMINAL_ENTRIES);
    }

    void methodExceptionTests(Executable fn, Enum<?> key) {
        var exc = Assertions.assertThrows(DAOException.class, fn);
        Assertions.assertEquals(key, exc.key);
    }
}
