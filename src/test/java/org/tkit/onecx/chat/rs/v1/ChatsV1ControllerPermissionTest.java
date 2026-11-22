package org.tkit.onecx.chat.rs.v1;

import static io.restassured.RestAssured.given;
import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;
import static jakarta.ws.rs.core.Response.Status.FORBIDDEN;

import org.junit.jupiter.api.Test;
import org.tkit.onecx.chat.test.AbstractTest;
import org.tkit.quarkus.security.test.GenerateKeycloakClient;
import org.tkit.quarkus.test.WithDBData;

import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;

/**
 * {@code GET /v1/chats/{chatId}} requires the {@code ocx-chat:read} permission. A client without that scope
 * is rejected with 403 before the controller runs, even when the principal owns the chat.
 * <p>
 * Separate class because {@link GenerateKeycloakClient} is a single, class-level annotation.
 */
@QuarkusTest
@TestHTTPEndpoint(ChatsV1Controller.class)
@WithDBData(value = "data/testdata-v1.xml", deleteBeforeInsert = true, deleteAfterTest = true, rinseAndRepeat = true)
@GenerateKeycloakClient(clientName = "testClientNoRead", scopes = { "ocx-chat:write" })
class ChatsV1ControllerPermissionTest extends AbstractTest {

    @Test
    void clientWithoutReadScopeReceivesForbidden() {
        given()
                .auth().oauth2(getKeycloakClientToken("testClientNoRead"))
                .contentType(APPLICATION_JSON)
                .header(APM_HEADER_PARAM, createToken("org1"))
                .pathParam("chatId", "t-chat-v1-owner-01")
                .get()
                .then()
                .statusCode(FORBIDDEN.getStatusCode());
    }
}
