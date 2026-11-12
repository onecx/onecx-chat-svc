package org.tkit.onecx.chat.domain.daos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.LiquibaseException;
import liquibase.resource.ClassLoaderResourceAccessor;

@QuarkusTest
class ConversationEntryMigrationTest {

    @Inject
    DataSource dataSource;

    @Test
    void backfillsMessagesInTenantScopedDeterministicOrderAndIsRepeatable() throws Exception {
        var schema = "migration_test_" + UUID.randomUUID().toString().replace("-", "");
        try {
            createLegacySchema(schema);
            runMigration(schema);
            assertMigratedRows(schema);
            runMigration(schema);
            assertMigratedRows(schema);
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void rejectsMessagesWithoutTenantOwnedChatsAndRollsBackSchemaChanges() throws Exception {
        var schema = "migration_test_" + UUID.randomUUID().toString().replace("-", "");
        try {
            createLegacySchema(schema);
            try (Connection connection = dataSource.getConnection();
                    Statement statement = connection.createStatement()) {
                connection.setSchema(schema);
                statement.execute("""
                        INSERT INTO message
                            (guid, optlock, tenant_id, chat_id, type, text, creationdate, creationuser)
                        VALUES
                            ('orphan', 0, 'tenant-a', 'missing-chat', 'HUMAN', 'orphan',
                             '2026-11-12 12:00:00', 'writer')
                        """);
            }

            assertThrows(LiquibaseException.class, () -> runMigration(schema));

            try (Connection connection = dataSource.getConnection();
                    Statement statement = connection.createStatement();
                    ResultSet result = statement.executeQuery("""
                            SELECT COUNT(*)
                            FROM information_schema.columns
                            WHERE table_schema = '%s'
                              AND table_name = 'conversation_entry'
                              AND column_name = 'user_id'
                            """.formatted(schema))) {
                result.next();
                assertEquals(0, result.getInt(1));
            }
        } finally {
            dropSchema(schema);
        }
    }

    private void createLegacySchema(String schema) throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("CREATE SCHEMA " + schema);
            connection.setSchema(schema);
            statement.execute("""
                    CREATE TABLE chat (
                        guid VARCHAR(255) PRIMARY KEY,
                        tenant_id VARCHAR(255) NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE message (
                        guid VARCHAR(255) PRIMARY KEY,
                        optlock INTEGER NOT NULL,
                        tenant_id VARCHAR(255) NOT NULL,
                        chat_id VARCHAR(255),
                        type VARCHAR(255),
                        text VARCHAR(10000),
                        user_id VARCHAR(255),
                        creationdate TIMESTAMP WITHOUT TIME ZONE,
                        modificationdate TIMESTAMP WITHOUT TIME ZONE,
                        creationuser VARCHAR(255),
                        modificationuser VARCHAR(255)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE conversation_entry (
                        guid VARCHAR(255) PRIMARY KEY,
                        optlock INTEGER NOT NULL,
                        tenant_id VARCHAR(255) NOT NULL,
                        chat_id VARCHAR(255) NOT NULL,
                        sequence_no BIGINT NOT NULL,
                        idempotency_key VARCHAR(255) NOT NULL,
                        text VARCHAR(10000),
                        type VARCHAR(255) NOT NULL,
                        status VARCHAR(255) NOT NULL,
                        creationdate TIMESTAMP WITHOUT TIME ZONE,
                        modificationdate TIMESTAMP WITHOUT TIME ZONE,
                        creationuser VARCHAR(255),
                        modificationuser VARCHAR(255)
                    )
                    """);
            statement.execute("""
                    INSERT INTO chat (guid, tenant_id) VALUES
                        ('chat-a', 'tenant-a'),
                        ('chat-b', 'tenant-b')
                    """);
            statement.execute("""
                    INSERT INTO conversation_entry
                        (guid, optlock, tenant_id, chat_id, sequence_no, idempotency_key, text, type, status,
                         creationdate, modificationdate, creationuser, modificationuser)
                    VALUES
                        ('voice-entry', 2, 'tenant-a', 'chat-a', 5, 'voice-key', 'Voice', 'HUMAN', 'COMPLETED',
                         '2026-11-10 10:00:00', '2026-11-10 10:00:00', 'voice-user', 'voice-user'),
                        ('existing-entry', 3, 'tenant-a', 'chat-a', 7, 'existing-key', 'Existing', 'ASSISTANT',
                         'COMPLETED', '2026-11-10 10:01:00', '2026-11-10 10:01:00', 'assistant', 'assistant')
                    """);
            statement.execute("""
                    INSERT INTO message
                        (guid, optlock, tenant_id, chat_id, type, text, user_id, creationdate, modificationdate,
                         creationuser, modificationuser)
                    VALUES
                        ('m-early', 4, 'tenant-a', 'chat-a', 'HUMAN', 'Early', 'user-a',
                         '2026-11-11 08:00:00', '2026-11-11 08:00:01', 'writer', 'auditor'),
                        ('z-tie', 5, 'tenant-a', 'chat-a', 'HUMAN', 'Later tie', 'user-b',
                         '2026-11-11 09:00:00', '2026-11-11 09:00:01', 'writer', 'auditor'),
                        ('a-tie', 6, 'tenant-a', 'chat-a', 'HUMAN', 'Earlier tie', 'user-c',
                         '2026-11-11 09:00:00', '2026-11-11 09:00:01', 'writer', 'auditor'),
                        ('m-null', 7, 'tenant-a', 'chat-a', 'SYSTEM', '', 'system',
                         NULL, NULL, 'system-user', 'system-user'),
                        ('voice-message', 8, 'tenant-a', 'chat-a', 'HUMAN', 'Voice', NULL,
                         '2026-11-10 10:00:00', '2026-11-10 10:00:00', 'voice-user', 'voice-user'),
                        ('tenant-message', 9, 'tenant-b', 'chat-b', 'ASSISTANT', 'Tenant B', 'assistant',
                         '2026-11-12 11:00:00', '2026-11-12 11:00:01', 'assistant', 'auditor')
                    """);
            connection.commit();
        }
    }

    private void runMigration(String schema) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setSchema(schema);
            var database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            database.setDefaultSchemaName(schema);
            database.setLiquibaseSchemaName(schema);
            var liquibase = new Liquibase("db/v1/2026-11-12-migrate-typed-messages-to-conversation-entries.xml",
                    new ClassLoaderResourceAccessor(), database);
            try {
                liquibase.update(new Contexts(), new LabelExpression());
            } finally {
                liquibase.close();
            }
        }
    }

    private void assertMigratedRows(String schema) throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            connection.setSchema(schema);
            try (ResultSet result = statement.executeQuery("""
                    SELECT guid, sequence_no
                    FROM conversation_entry
                    WHERE idempotency_key LIKE 'legacy-message:%' AND chat_id = 'chat-a'
                    ORDER BY sequence_no
                    """)) {
                var ids = new ArrayList<String>();
                var sequences = new ArrayList<Long>();
                while (result.next()) {
                    ids.add(result.getString("guid"));
                    sequences.add(result.getLong("sequence_no"));
                }
                assertEquals(List.of("m-early", "a-tie", "z-tie", "m-null"), ids);
                assertEquals(List.of(8L, 9L, 10L, 11L), sequences);
            }

            try (ResultSet result = statement.executeQuery("""
                    SELECT type, status, message_type, user_id, optlock, creationuser, modificationuser
                    FROM conversation_entry
                    WHERE guid = 'm-early'
                    """)) {
                result.next();
                assertEquals("TEXT", result.getString("type"));
                assertEquals("COMPLETED", result.getString("status"));
                assertEquals("HUMAN", result.getString("message_type"));
                assertEquals("user-a", result.getString("user_id"));
                assertEquals(4, result.getInt("optlock"));
                assertEquals("writer", result.getString("creationuser"));
                assertEquals("auditor", result.getString("modificationuser"));
            }

            try (ResultSet result = statement.executeQuery("""
                    SELECT legacy_message_id
                    FROM conversation_entry
                    WHERE guid = 'voice-entry'
                    """)) {
                result.next();
                assertEquals("voice-message", result.getString("legacy_message_id"));
            }

            try (ResultSet result = statement.executeQuery("""
                    SELECT type, message_type, text, sequence_no
                    FROM conversation_entry
                    WHERE guid = 'm-null'
                    """)) {
                result.next();
                assertEquals("TEXT", result.getString("type"));
                assertEquals("SYSTEM", result.getString("message_type"));
                assertEquals("", result.getString("text"));
                assertEquals(11L, result.getLong("sequence_no"));
            }

            try (ResultSet result = statement.executeQuery("""
                    SELECT COUNT(*)
                    FROM conversation_entry
                    WHERE chat_id = 'chat-a' AND tenant_id = 'tenant-a'
                    """)) {
                result.next();
                assertEquals(6, result.getInt(1));
            }
            try (ResultSet result = statement.executeQuery("""
                    SELECT COUNT(*)
                    FROM message
                    WHERE chat_id = 'chat-a' AND tenant_id = 'tenant-a'
                    """)) {
                result.next();
                assertEquals(5, result.getInt(1));
            }
            try (ResultSet result = statement.executeQuery("""
                    SELECT sequence_no, tenant_id, message_type
                    FROM conversation_entry
                    WHERE guid = 'tenant-message'
                    """)) {
                result.next();
                assertEquals(1L, result.getLong("sequence_no"));
                assertEquals("tenant-b", result.getString("tenant_id"));
                assertEquals("ASSISTANT", result.getString("message_type"));
            }
        }
    }

    private void dropSchema(String schema) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }
}
