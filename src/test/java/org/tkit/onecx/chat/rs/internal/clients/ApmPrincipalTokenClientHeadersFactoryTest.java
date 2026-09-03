package org.tkit.onecx.chat.rs.internal.clients;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.ws.rs.core.MultivaluedHashMap;

import org.junit.jupiter.api.Test;

class ApmPrincipalTokenClientHeadersFactoryTest {

    private final ApmPrincipalTokenClientHeadersFactory factory = new ApmPrincipalTokenClientHeadersFactory();

    @Test
    void shouldUseIncomingHeaderWhenPresent() {
        var incomingHeaders = new MultivaluedHashMap<String, String>();
        incomingHeaders.putSingle(ApmPrincipalTokenContext.HEADER_NAME, "incoming-token");

        var result = factory.update(incomingHeaders, new MultivaluedHashMap<>());

        assertThat(result.getFirst(ApmPrincipalTokenContext.HEADER_NAME)).isEqualTo("incoming-token");
    }

    @Test
    void shouldFallbackToThreadLocalTokenWhenIncomingHeaderMissing() {
        try (var _ = ApmPrincipalTokenContext.withToken("thread-token")) {
            var result = factory.update(new MultivaluedHashMap<>(), new MultivaluedHashMap<>());

            assertThat(result.getFirst(ApmPrincipalTokenContext.HEADER_NAME)).isEqualTo("thread-token");
        }
    }

    @Test
    void shouldReturnHeadersUnchangedWhenAlreadyContainsToken() {
        var clientOutgoingHeaders = new MultivaluedHashMap<String, String>();
        clientOutgoingHeaders.putSingle(ApmPrincipalTokenContext.HEADER_NAME, "existing-token");

        var result = factory.update(new MultivaluedHashMap<>(), clientOutgoingHeaders);

        assertThat(result.getFirst(ApmPrincipalTokenContext.HEADER_NAME)).isEqualTo("existing-token");
    }

    @Test
    void shouldNotAddTokenWhenBothIncomingAndThreadLocalAreBlank() {
        var incomingHeaders = new MultivaluedHashMap<String, String>();
        incomingHeaders.putSingle(ApmPrincipalTokenContext.HEADER_NAME, "   ");

        var result = factory.update(incomingHeaders, new MultivaluedHashMap<>());

        assertThat(result.containsKey(ApmPrincipalTokenContext.HEADER_NAME)).isFalse();
    }

    @Test
    void shouldNotAddTokenWhenBothIncomingAndThreadLocalAreNull() {
        var result = factory.update(new MultivaluedHashMap<>(), new MultivaluedHashMap<>());

        assertThat(result.containsKey(ApmPrincipalTokenContext.HEADER_NAME)).isFalse();
    }

    @Test
    void shouldHandleNullIncomingHeaders() {
        var clientOutgoingHeaders = new MultivaluedHashMap<String, String>();

        try (var _ = ApmPrincipalTokenContext.withToken("thread-token")) {
            var result = factory.update(null, clientOutgoingHeaders);

            assertThat(result.getFirst(ApmPrincipalTokenContext.HEADER_NAME)).isEqualTo("thread-token");
        }
    }
}
