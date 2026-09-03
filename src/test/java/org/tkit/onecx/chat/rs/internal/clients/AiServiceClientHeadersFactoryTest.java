package org.tkit.onecx.chat.rs.internal.clients;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;

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
        try (var _ = ApmPrincipalTokenContext.withToken("apm-thread");
                var _ = AiServiceUserAuthorizationContext.withHeader("Bearer thread-user")) {
            var result = factory.update(new MultivaluedHashMap<>(), new MultivaluedHashMap<>());

            assertThat(result.getFirst(ApmPrincipalTokenContext.HEADER_NAME)).isEqualTo("apm-thread");
            assertThat(result.getFirst(AiServiceClientHeadersFactory.USER_AUTHORIZATION_HEADER_NAME))
                    .isEqualTo("Bearer thread-user");
        }
    }

    @Test
    void shouldReturnHeadersUnchangedWhenAlreadyContainsApmHeader() {
        var clientOutgoingHeaders = new MultivaluedHashMap<String, String>();
        clientOutgoingHeaders.putSingle(ApmPrincipalTokenContext.HEADER_NAME, "existing-apm-token");

        var result = factory.update(new MultivaluedHashMap<>(), clientOutgoingHeaders);

        assertThat(result.getFirst(ApmPrincipalTokenContext.HEADER_NAME)).isEqualTo("existing-apm-token");
    }

    @Test
    void shouldReturnHeadersUnchangedWhenAlreadyContainsUserAuthorizationHeader() {
        var clientOutgoingHeaders = new MultivaluedHashMap<String, String>();
        clientOutgoingHeaders.putSingle(AiServiceClientHeadersFactory.USER_AUTHORIZATION_HEADER_NAME, "Bearer existing-user");

        var result = factory.update(new MultivaluedHashMap<>(), clientOutgoingHeaders);

        assertThat(result.getFirst(AiServiceClientHeadersFactory.USER_AUTHORIZATION_HEADER_NAME))
                .isEqualTo("Bearer existing-user");
    }

    @Test
    void shouldHandleNullIncomingHeaders() {
        var result = factory.update(null, new MultivaluedHashMap<>());

        assertThat(result).isNotNull();
    }

    @Test
    void shouldNotAddHeadersWhenAllValuesAreBlank() {
        var incomingHeaders = new MultivaluedHashMap<String, String>();
        incomingHeaders.putSingle(ApmPrincipalTokenContext.HEADER_NAME, "   ");
        incomingHeaders.putSingle(AiServiceClientHeadersFactory.USER_AUTHORIZATION_HEADER_NAME, "   ");

        var result = factory.update(incomingHeaders, new MultivaluedHashMap<>());

        assertThat(result.containsKey(ApmPrincipalTokenContext.HEADER_NAME)).isFalse();
        assertThat(result.containsKey(AiServiceClientHeadersFactory.USER_AUTHORIZATION_HEADER_NAME)).isFalse();
    }

    @Test
    void shouldAddIncomingValueWhenNotBlank() {
        var incomingHeaders = new MultivaluedHashMap<String, String>();
        incomingHeaders.putSingle(ApmPrincipalTokenContext.HEADER_NAME, "valid-apm-token");

        var result = factory.update(incomingHeaders, new MultivaluedHashMap<>());

        assertThat(result.getFirst(ApmPrincipalTokenContext.HEADER_NAME)).isEqualTo("valid-apm-token");
    }

    @Test
    void shouldNotAddHeaderWhenIncomingIsNullAndFallbackIsBlank() {
        var result = factory.update(new MultivaluedHashMap<>(), new MultivaluedHashMap<>());

        assertThat(result.containsKey(ApmPrincipalTokenContext.HEADER_NAME)).isFalse();
        assertThat(result.containsKey(AiServiceClientHeadersFactory.USER_AUTHORIZATION_HEADER_NAME)).isFalse();
    }

    @Test
    void shouldUseFallbackWhenIncomingIsNullButFallbackIsValid() {
        try (var _ = ApmPrincipalTokenContext.withToken("fallback-apm-token")) {
            var incomingHeaders = new MultivaluedHashMap<String, String>();

            var result = factory.update(incomingHeaders, new MultivaluedHashMap<>());

            assertThat(result.getFirst(ApmPrincipalTokenContext.HEADER_NAME)).isEqualTo("fallback-apm-token");
        }
    }

    @Test
    void shouldUseFallbackWhenIncomingIsBlankButFallbackIsValid() {
        try (var _ = AiServiceUserAuthorizationContext.withHeader("Bearer fallback-user")) {
            var incomingHeaders = new MultivaluedHashMap<String, String>();
            incomingHeaders.putSingle(AiServiceClientHeadersFactory.USER_AUTHORIZATION_HEADER_NAME, "   ");

            var result = factory.update(incomingHeaders, new MultivaluedHashMap<>());

            assertThat(result.getFirst(AiServiceClientHeadersFactory.USER_AUTHORIZATION_HEADER_NAME))
                    .isEqualTo("Bearer fallback-user");
        }
    }

    @Test
    void shouldNotAddHeaderWhenBothIncomingAndFallbackAreBlank() {
        var incomingHeaders = new MultivaluedHashMap<String, String>();
        incomingHeaders.putSingle(ApmPrincipalTokenContext.HEADER_NAME, "   ");

        var result = factory.update(incomingHeaders, new MultivaluedHashMap<>());

        assertThat(result.containsKey(ApmPrincipalTokenContext.HEADER_NAME)).isFalse();
    }

    @Test
    void shouldHandleEmptyHeadersList() {
        var incomingHeaders = new MultivaluedHashMap<String, String>();
        incomingHeaders.put(ApmPrincipalTokenContext.HEADER_NAME, new ArrayList<>());

        var result = factory.update(incomingHeaders, new MultivaluedHashMap<>());

        assertThat(result.containsKey(ApmPrincipalTokenContext.HEADER_NAME)).isFalse();
    }
}
