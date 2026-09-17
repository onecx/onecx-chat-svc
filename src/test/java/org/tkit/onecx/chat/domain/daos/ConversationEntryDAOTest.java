package org.tkit.onecx.chat.domain.daos;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mockito.Mockito;
import org.tkit.onecx.chat.domain.models.Chat;
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
        Mockito.when(em.getCriteriaBuilder())
                .thenThrow(new RuntimeException("Test technical error exception"));
    }

    @Test
    void methodExceptionTests() {

        methodExceptionTests(
                () -> dao.findByChatAndIdempotencyKey(Mockito.mock(Chat.class), "idempotency-key"),
                ConversationEntryDAO.ErrorKeys.ERROR_FIND_CONVERSATION_ENTRY_BY_IDEMPOTENCY_KEY);

        methodExceptionTests(
                () -> dao.findMaxSequenceByChat(Mockito.mock(Chat.class)),
                ConversationEntryDAO.ErrorKeys.ERROR_FIND_CONVERSATION_ENTRY_MAX_SEQUENCE);

        methodExceptionTests(
                () -> dao.findReplayEntries(Mockito.mock(Chat.class)),
                ConversationEntryDAO.ErrorKeys.ERROR_FIND_CONVERSATION_ENTRY_REPLAY_ENTRIES);
    }

    void methodExceptionTests(Executable fn, Enum<?> key) {
        var exc = Assertions.assertThrows(DAOException.class, fn);
        Assertions.assertEquals(key, exc.key);
    }
}