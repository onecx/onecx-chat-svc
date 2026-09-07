package org.tkit.onecx.chat.rs.internal.controllers;

import static io.restassured.RestAssured.given;
import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;
import static jakarta.ws.rs.core.Response.Status.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.tkit.onecx.chat.test.AbstractTest;
import org.tkit.quarkus.security.test.GenerateKeycloakClient;
import org.tkit.quarkus.test.WithDBData;

import gen.org.tkit.onecx.chat.rs.internal.model.ConversationEntryDTO;
import gen.org.tkit.onecx.chat.rs.internal.model.ConversationEntryTypeDTO;
import gen.org.tkit.onecx.chat.rs.internal.model.CreateOrUpdateConversationEntryDTO;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.common.mapper.TypeRef;

@QuarkusTest
@TestHTTPEndpoint(ConversationEntriesRestController.class)
@WithDBData(value = "data/testdata-internal.xml", deleteBeforeInsert = true, deleteAfterTest = true, rinseAndRepeat = true)
@GenerateKeycloakClient(clientName = "testClient", scopes = { "ocx-chat:all", "ocx-chat-entries:read",
        "ocx-chat-entries:write" })
class ConversationEntriesRestControllerTest extends AbstractTest {

    private static CreateOrUpdateConversationEntryDTO dtoWithText(String idempotencyKey, String text) {
        var dto = new CreateOrUpdateConversationEntryDTO();
        dto.setIdempotencyKey(idempotencyKey);
        dto.setEntryType(ConversationEntryTypeDTO.HUMAN_UTTERANCE);
        dto.setText(text);
        return dto;
    }

    @Test
    void createConversationEntryReturnsCreatedWithSequenceOne() {
        var dto = dtoWithText("http-idem-1", "Hi");

        var result = given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .contentType(APPLICATION_JSON)
                .body(dto)
                .pathParam("chatId", "chat-11-111")
                .post()
                .then()
                .statusCode(CREATED.getStatusCode())
                .extract()
                .body().as(ConversationEntryDTO.class);

        assertThat(result.getSequenceNumber()).isEqualTo(1L);
    }

    @Test
    void repeatedIdenticalPostReturnsOkNoOp() {
        var dto = dtoWithText("http-idem-2", "Hi");

        var first = given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .contentType(APPLICATION_JSON)
                .body(dto)
                .pathParam("chatId", "chat-11-111")
                .post()
                .then()
                .statusCode(CREATED.getStatusCode())
                .extract()
                .body().as(ConversationEntryDTO.class);

        var second = given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .contentType(APPLICATION_JSON)
                .body(dto)
                .pathParam("chatId", "chat-11-111")
                .post()
                .then()
                .statusCode(OK.getStatusCode())
                .extract()
                .body().as(ConversationEntryDTO.class);

        assertThat(second.getSequenceNumber()).isEqualTo(first.getSequenceNumber());
    }

    @Test
    void monotonicGrowthPostReturnsOkWithUpdatedText() {
        given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .contentType(APPLICATION_JSON)
                .body(dtoWithText("http-idem-3", "Hel"))
                .pathParam("chatId", "chat-11-111")
                .post()
                .then()
                .statusCode(CREATED.getStatusCode());

        var second = given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .contentType(APPLICATION_JSON)
                .body(dtoWithText("http-idem-3", "Hello"))
                .pathParam("chatId", "chat-11-111")
                .post()
                .then()
                .statusCode(OK.getStatusCode())
                .extract()
                .body().as(ConversationEntryDTO.class);

        assertThat(second.getText()).isEqualTo("Hello");
    }

    @Test
    void incompatibleRetryPostReturnsConflict() {
        given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .contentType(APPLICATION_JSON)
                .body(dtoWithText("http-idem-4", "Hello"))
                .pathParam("chatId", "chat-11-111")
                .post()
                .then()
                .statusCode(CREATED.getStatusCode());

        given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .contentType(APPLICATION_JSON)
                .body(dtoWithText("http-idem-4", "Goodbye"))
                .pathParam("chatId", "chat-11-111")
                .post()
                .then()
                .statusCode(CONFLICT.getStatusCode());
    }

    @Test
    void postToUnknownChatReturnsNotFound() {
        given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .contentType(APPLICATION_JSON)
                .body(dtoWithText("http-idem-5", "Hi"))
                .pathParam("chatId", "unknown-chat-id")
                .post()
                .then()
                .statusCode(NOT_FOUND.getStatusCode());
    }

    @Test
    void getConversationEntriesReturnsOnlyTerminalInSequenceOrder() {
        var result = given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .contentType(APPLICATION_JSON)
                .pathParam("chatId", "chat-22-222")
                .get()
                .then()
                .statusCode(OK.getStatusCode())
                .extract()
                .body().as(new TypeRef<List<ConversationEntryDTO>>() {
                });

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo("entry-11-111");
    }

    @Test
    void getConversationEntriesForUnknownChatReturnsNotFound() {
        given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .contentType(APPLICATION_JSON)
                .pathParam("chatId", "unknown-chat-id")
                .get()
                .then()
                .statusCode(NOT_FOUND.getStatusCode());
    }
}
