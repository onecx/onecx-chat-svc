package org.tkit.onecx.chat.rs.internal.controllers;

import static io.restassured.RestAssured.given;
import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;
import static jakarta.ws.rs.core.Response.Status.CREATED;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tkit.onecx.chat.test.AbstractTest;
import org.tkit.quarkus.security.test.GenerateKeycloakClient;
import org.tkit.quarkus.test.WithDBData;

import gen.org.tkit.onecx.chat.rs.internal.model.CreateChatDTO;
import gen.org.tkit.onecx.chat.rs.internal.model.ChatTypeDTO;
import gen.org.tkit.onecx.chat.rs.internal.model.RegisterToolCallDTO;
import gen.org.tkit.onecx.chat.rs.internal.model.ToolCallClaimDTO;
import gen.org.tkit.onecx.chat.rs.internal.model.ToolCallDecisionDTO;
import gen.org.tkit.onecx.chat.rs.internal.model.ToolCallPolicyDecisionDTO;
import gen.org.tkit.onecx.chat.rs.internal.model.ToolCallTerminalDecisionDTO;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@WithDBData(value = "data/testdata-internal.xml", deleteBeforeInsert = true, rinseAndRepeat = true)
@GenerateKeycloakClient(clientName = "toolRuntimeClient", scopes = {
        "ocx-chat:all", "ocx-chat:read", "ocx-chat:write", "ocx-chat:tool-runtime"
})
class ToolCallsRestControllerTest extends AbstractTest {

    private String token;

    @BeforeEach
    void setUp() {
        token = getKeycloakClientToken("toolRuntimeClient");
    }

    @Test
    void databaseSerializesConcurrentDecisionsAndClaimsWithScopePermissions() throws Exception {
        String decisionChatId = createChat();
        String proposalId = register(decisionChatId, ToolCallPolicyDecisionDTO.ALWAYS_ASK);
        List<Integer> decisionStatuses = race(
                () -> decide(decisionChatId, proposalId, ToolCallTerminalDecisionDTO.APPROVED),
                () -> decide(decisionChatId, proposalId, ToolCallTerminalDecisionDTO.REJECTED));
        assertThat(decisionStatuses).containsExactlyInAnyOrder(200, 409);

        String claimsChatId = createChat();
        String firstAttempt = register(claimsChatId, ToolCallPolicyDecisionDTO.ALLOW);
        String secondAttempt = register(claimsChatId, ToolCallPolicyDecisionDTO.ALLOW);
        List<Integer> claimStatuses = race(
                () -> claim(claimsChatId, firstAttempt),
                () -> claim(claimsChatId, secondAttempt));
        assertThat(claimStatuses).containsExactlyInAnyOrder(CREATED.getStatusCode(), 409);
    }

    private String createChat() {
        CreateChatDTO request = new CreateChatDTO();
        request.setAppId("tool-call-lifecycle-test");
        request.setType(ChatTypeDTO.AI_CHAT);
        return given()
                .auth().oauth2(token)
                .header(APM_HEADER_PARAM, createToken("org1"))
                .contentType(APPLICATION_JSON)
                .body(request)
                .post("/internal/chats")
                .then()
                .statusCode(CREATED.getStatusCode())
                .extract()
                .path("id");
    }

    private String register(String chatId, ToolCallPolicyDecisionDTO policy) {
        String attemptId = UUID.randomUUID().toString();
        RegisterToolCallDTO request = new RegisterToolCallDTO();
        request.setAttemptId(attemptId);
        request.setIdempotencyKey(UUID.randomUUID().toString());
        request.setToolName("search");
        request.setArguments("{\"query\":\"safe\"}");
        request.setConfigurationSnapshotHash("configuration-hash");
        request.setSchemaHash("schema-hash");
        request.setArgumentHash("argument-hash");
        request.setProposingPrincipal("test-user");
        request.setRuntime("provider-runtime");
        request.setPolicyDecision(policy);
        request.setSafeSummary("Search request");
        given()
                .auth().oauth2(token)
                .header(APM_HEADER_PARAM, createToken("org1"))
                .contentType(APPLICATION_JSON)
                .body(request)
                .post("/internal/chats/{chatId}/tool-calls", chatId)
                .then()
                .statusCode(CREATED.getStatusCode());
        return attemptId;
    }

    private int decide(String chatId, String attemptId, ToolCallTerminalDecisionDTO decision) {
        ToolCallDecisionDTO request = new ToolCallDecisionDTO();
        request.setIdempotencyKey(UUID.randomUUID().toString());
        request.setDecision(decision);
        return given()
                .auth().oauth2(token)
                .header(APM_HEADER_PARAM, createToken("org1"))
                .contentType(APPLICATION_JSON)
                .body(request)
                .post("/internal/chats/{chatId}/tool-calls/{attemptId}/decision", chatId, attemptId)
                .statusCode();
    }

    private int claim(String chatId, String attemptId) {
        ToolCallClaimDTO request = new ToolCallClaimDTO();
        request.setIdempotencyKey(UUID.randomUUID().toString());
        return given()
                .auth().oauth2(token)
                .header(APM_HEADER_PARAM, createToken("org1"))
                .contentType(APPLICATION_JSON)
                .body(request)
                .post("/internal/chats/{chatId}/tool-calls/{attemptId}/claims", chatId, attemptId)
                .statusCode();
    }

    private List<Integer> race(ConcurrentRequest first, ConcurrentRequest second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> firstResult = executor.submit(() -> awaitAndRun(first, ready, start));
            Future<Integer> secondResult = executor.submit(() -> awaitAndRun(second, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Integer> statuses = new ArrayList<>(2);
            statuses.add(firstResult.get(30, TimeUnit.SECONDS));
            statuses.add(secondResult.get(30, TimeUnit.SECONDS));
            return statuses;
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private int awaitAndRun(ConcurrentRequest request, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent lifecycle request was not released.");
        }
        return request.run();
    }

    @FunctionalInterface
    private interface ConcurrentRequest {
        int run();
    }
}
