package org.tkit.onecx.chat.rs.internal.clients;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.ws.rs.core.MultivaluedHashMap;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class AiServiceClientHeadersFactoryTest {

    private final AiServiceClientHeadersFactory factory = new AiServiceClientHeadersFactory();

    @Test
    void shouldUseIncomingHeadersWhenPresent() {
        var incomingHeaders = new MultivaluedHashMap<String, String>();
        incomingHeaders.putSingle(ApmPrincipalTokenContext.HEADER_NAME, "apm-incoming");
        incomingHeaders.putSingle(AiServiceClientHeadersFactory.USER_AUTHORIZATION_HEADER_NAME, "Bearer incoming-user");

        var result = factory.update(incomingHeaders, new MultivaluedHashMap<>());

        assertThat(result.getFirst(ApmPrincipalTokenContext.HEADER_NAME)).isEqualTo("apm-incoming");
        assertThat(result.getFirst(AiServiceClientHeadersFactory.USER_AUTHORIZATION_HEADER_NAME))
                .isEqualTo("Bearer incoming-user");
    }

    @Test
    void shouldFallbackToThreadLocalHeadersWhenIncomingMissing() {
        try (var apmScope = ApmPrincipalTokenContext.withToken("apm-thread");
                var userScope = AiServiceUserAuthorizationContext.withHeader("Bearer thread-user")) {
            var result = factory.update(new MultivaluedHashMap<>(), new MultivaluedHashMap<>());

            assertThat(result.getFirst(ApmPrincipalTokenContext.HEADER_NAME)).isEqualTo("apm-thread");
            assertThat(result.getFirst(AiServiceClientHeadersFactory.USER_AUTHORIZATION_HEADER_NAME))
                    .isEqualTo("Bearer thread-user");
        }
    }
}
