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
class ConversationEntriesRestControllerTenantTest extends AbstractTest {

    @Test
    void getConversationEntriesCrossTenantReturnsNotFound() {
        given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .contentType(APPLICATION_JSON)
                .header(APM_HEADER_PARAM, createToken("org2"))
                .pathParam("chatId", "t-chat-22-222")
                .get()
                .then()
                .statusCode(NOT_FOUND.getStatusCode());
    }

    @Test
    void getConversationEntriesSameTenantReturnsBothTerminalEntriesInOrder() {
        var result = given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .contentType(APPLICATION_JSON)
                .header(APM_HEADER_PARAM, createToken("org1"))
                .pathParam("chatId", "t-chat-22-222")
                .get()
                .then()
                .statusCode(OK.getStatusCode())
                .extract()
                .body().as(new TypeRef<List<ConversationEntryDTO>>() {
                });

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getId()).isEqualTo("t-entry-11-111");
        assertThat(result.get(0).getSequenceNumber()).isEqualTo(1L);
        assertThat(result.get(1).getId()).isEqualTo("t-entry-22-222");
        assertThat(result.get(1).getSequenceNumber()).isEqualTo(2L);
    }

    @Test
    void createConversationEntryCrossTenantReturnsNotFound() {
        var dto = new CreateOrUpdateConversationEntryDTO();
        dto.setIdempotencyKey("cross-tenant-1");
        dto.setEntryType(ConversationEntryTypeDTO.HUMAN_UTTERANCE);
        dto.setText("Hi");

        given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .contentType(APPLICATION_JSON)
                .header(APM_HEADER_PARAM, createToken("org2"))
                .body(dto)
                .pathParam("chatId", "t-chat-22-222")
                .post()
                .then()
                .statusCode(NOT_FOUND.getStatusCode());
    }
}
