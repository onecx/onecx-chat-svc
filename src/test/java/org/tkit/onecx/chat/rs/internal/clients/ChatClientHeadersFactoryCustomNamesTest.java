package org.tkit.onecx.chat.rs.internal.clients;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.inject.Inject;
import jakarta.ws.rs.core.MultivaluedHashMap;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(CustomHeaderNamesProfile.class)
class ChatClientHeadersFactoryCustomNamesTest {

    @Inject
    ChatClientHeadersFactory factory;

    @Test
    void shouldUseConfiguredHeaderNames() {
        var incomingHeaders = new MultivaluedHashMap<String, String>();
        incomingHeaders.putSingle(CustomHeaderNamesProfile.PRINCIPAL_TOKEN_HEADER, "apm-incoming");
        incomingHeaders.putSingle(CustomHeaderNamesProfile.USER_AUTHORIZATION_HEADER, "Bearer incoming-user");

        var result = factory.update(incomingHeaders, new MultivaluedHashMap<>());

        assertThat(result.getFirst(CustomHeaderNamesProfile.PRINCIPAL_TOKEN_HEADER)).isEqualTo("apm-incoming");
        assertThat(result.getFirst(CustomHeaderNamesProfile.USER_AUTHORIZATION_HEADER)).isEqualTo("Bearer incoming-user");
        assertThat(result.containsKey("apm-principal-token")).isFalse();
        assertThat(result.containsKey("UserAuthorization")).isFalse();
    }

    @Test
    void shouldFallbackToThreadLocalWithConfiguredNames() {
        try (var _ = ApmPrincipalTokenContext.withToken("apm-thread");
                var _ = AiServiceUserAuthorizationContext.withHeader("Bearer thread-user")) {
            var result = factory.update(new MultivaluedHashMap<>(), new MultivaluedHashMap<>());

            assertThat(result.getFirst(CustomHeaderNamesProfile.PRINCIPAL_TOKEN_HEADER)).isEqualTo("apm-thread");
            assertThat(result.getFirst(CustomHeaderNamesProfile.USER_AUTHORIZATION_HEADER)).isEqualTo("Bearer thread-user");
        }
    }
}
