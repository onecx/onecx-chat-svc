package org.tkit.onecx.chat.rs.internal.controllers;

import static io.restassured.RestAssured.given;
import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;
import static jakarta.ws.rs.core.Response.Status.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

import java.time.Duration;
import java.util.List;

import jakarta.ws.rs.HttpMethod;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.matchers.MatchType;
import org.mockserver.model.JsonBody;
import org.tkit.onecx.chat.test.AbstractTest;
import org.tkit.quarkus.security.test.GenerateKeycloakClient;
import org.tkit.quarkus.test.WithDBData;

import gen.io.github.onecx.ai.clients.model.ChatMessage;
import gen.org.tkit.onecx.chat.rs.internal.model.*;
import io.quarkiverse.mockserver.test.InjectMockServerClient;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.common.mapper.TypeRef;

@QuarkusTest
@GenerateKeycloakClient(clientName = "testClient", scopes = { "ocx-chat:all", "ocx-chat:read", "ocx-chat:write" })
@WithDBData(value = "data/testdata-internal.xml", deleteBeforeInsert = true, deleteAfterTest = true, rinseAndRepeat = true)
@TestHTTPEndpoint(ChatsRestController.class)
class ChatRestControllerAIAsyncTest extends AbstractTest {

    @InjectMockServerClient
    public MockServerClient mockServerClient;

    static final String MOCK_ID = "MOCK";
    static final String MOCK_NOTIFICATION_ID = "MOCK_NOTIFICATION";

    @BeforeEach
    void resetExpectation() {
        try {
            mockServerClient.clear(MOCK_ID);
            mockServerClient.clear(MOCK_NOTIFICATION_ID);
        } catch (Exception _) {
            //  mockId not existing
        }
    }

    @Test
    void createChatMessageShouldReturnAcceptedWhenAwaitResponseFalseTest() {

        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setConversationId("123456");
        chatMessage.setMessage("AI generated response");
        chatMessage.setType(ChatMessage.TypeEnum.ASSISTANT);
        chatMessage.setCreationDate(1643684377000L);
        mockServerClient.when(request()
                .withPath("/v1/dispatch/chat")
                .withMethod(HttpMethod.POST))
                .withId(MOCK_ID)
                .respond(httpRequest -> {
                    // Delay dispatch response to prove API returns before AI processing finishes.
                    await().pollDelay(Duration.ofMillis(1500)).until(() -> true);
                    return response().withStatusCode(200)
                            .withContentType(org.mockserver.model.MediaType.APPLICATION_JSON)
                            .withBody(JsonBody.json(chatMessage));
                });

        var chatDto = new CreateChatDTO();
        chatDto.setAppId("appId");
        chatDto.setType(ChatTypeDTO.AI_CHAT);

        var chat = given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .when()
                .contentType(APPLICATION_JSON)
                .body(chatDto)
                .post()
                .then()
                .statusCode(CREATED.getStatusCode())
                .extract()
                .body().as(ChatDTO.class);

        Assertions.assertNotNull(chat);

        var messageDto = new CreateMessageDTO();
        messageDto.setType(MessageTypeDTO.HUMAN);
        messageDto.setText("Test question async AI");
        messageDto.setUserId("testUser");
        messageDto.setSkipAIProcessing(false);
        messageDto.setAwaitResponse(false);

        long startedAt = System.currentTimeMillis();
        given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .pathParam("chatId", chat.getId())
                .when()
                .contentType(APPLICATION_JSON)
                .body(messageDto)
                .post("{chatId}/messages")
                .then()
                .statusCode(ACCEPTED.getStatusCode());

        long elapsedMs = System.currentTimeMillis() - startedAt;
        assertThat(elapsedMs).isLessThan(1200L);

        var immediateMessages = given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .contentType(APPLICATION_JSON)
                .pathParam("chatId", chat.getId())
                .get("{chatId}/messages")
                .then()
                .statusCode(OK.getStatusCode())
                .extract().as(new TypeRef<List<MessageDTO>>() {
                });

        assertThat(immediateMessages).isNotNull().hasSize(1);
        assertThat(immediateMessages.get(0).getType()).isEqualTo(MessageTypeDTO.HUMAN);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            var eventualMessages = given()
                    .auth().oauth2(getKeycloakClientToken("testClient"))
                    .contentType(APPLICATION_JSON)
                    .pathParam("chatId", chat.getId())
                    .get("{chatId}/messages")
                    .then()
                    .statusCode(OK.getStatusCode())
                    .extract().as(new TypeRef<List<MessageDTO>>() {
                    });

            assertThat(eventualMessages).isNotNull().hasSize(2);
            assertThat(eventualMessages.get(1).getType()).isEqualTo(MessageTypeDTO.ASSISTANT);
        });

        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> mockServerClient.verify(dispatchRequestForChatId(chat.getId()),
                        org.mockserver.verify.VerificationTimes.atLeast(1)));
    }

    private org.mockserver.model.HttpRequest dispatchRequestForChatId(String chatId) {
        return request()
                .withPath("/v1/dispatch/chat")
                .withMethod(HttpMethod.POST)
                .withBody(JsonBody.json("""
                        {
                          "conversation": {
                            "conversationId": "%s"
                          }
                        }
                        """.formatted(chatId), MatchType.ONLY_MATCHING_FIELDS));
    }
}
