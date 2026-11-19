package org.tkit.onecx.chat.rs.internal.clients;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.ArrayList;

import jakarta.inject.Inject;
import jakarta.ws.rs.core.MultivaluedHashMap;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class ChatClientHeadersFactoryTest {

    private static final String APM_PRINCIPAL_TOKEN = "apm-principal-token";
    private static final String USER_AUTHORIZATION = "UserAuthorization";

    @Inject
    ChatClientHeadersFactory factory;

    @Test
    void shouldUseIncomingHeadersWhenPresent() {
        var incomingHeaders = new MultivaluedHashMap<String, String>();
        incomingHeaders.putSingle(APM_PRINCIPAL_TOKEN, "apm-incoming");
        incomingHeaders.putSingle(USER_AUTHORIZATION, "Bearer incoming-user");

        var result = factory.update(incomingHeaders, new MultivaluedHashMap<>());

        assertThat(result.getFirst(APM_PRINCIPAL_TOKEN)).isEqualTo("apm-incoming");
        assertThat(result.getFirst(USER_AUTHORIZATION))
                .isEqualTo("Bearer incoming-user");
    }

    @Test
    void shouldFallbackToThreadLocalHeadersWhenIncomingMissing() {
        try (var _ = ApmPrincipalTokenContext.withToken("apm-thread");
                var _ = AiServiceUserAuthorizationContext.withHeader("Bearer thread-user")) {
            var result = factory.update(new MultivaluedHashMap<>(), new MultivaluedHashMap<>());

            assertThat(result.getFirst(APM_PRINCIPAL_TOKEN)).isEqualTo("apm-thread");
            assertThat(result.getFirst(USER_AUTHORIZATION))
                    .isEqualTo("Bearer thread-user");
        }
    }

    @Test
    void shouldReturnHeadersUnchangedWhenAlreadyContainsApmHeader() {
        var clientOutgoingHeaders = new MultivaluedHashMap<String, String>();
        clientOutgoingHeaders.putSingle(APM_PRINCIPAL_TOKEN, "existing-apm-token");

        var result = factory.update(new MultivaluedHashMap<>(), clientOutgoingHeaders);

        assertThat(result.getFirst(APM_PRINCIPAL_TOKEN)).isEqualTo("existing-apm-token");
    }

    @Test
    void shouldReturnHeadersUnchangedWhenAlreadyContainsUserAuthorizationHeader() {
        var clientOutgoingHeaders = new MultivaluedHashMap<String, String>();
        clientOutgoingHeaders.putSingle(USER_AUTHORIZATION, "Bearer existing-user");

        var result = factory.update(new MultivaluedHashMap<>(), clientOutgoingHeaders);

        assertThat(result.getFirst(USER_AUTHORIZATION))
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
        incomingHeaders.putSingle(APM_PRINCIPAL_TOKEN, "   ");
        incomingHeaders.putSingle(USER_AUTHORIZATION, "   ");

        var result = factory.update(incomingHeaders, new MultivaluedHashMap<>());

        assertThat(result.containsKey(APM_PRINCIPAL_TOKEN)).isFalse();
        assertThat(result.containsKey(USER_AUTHORIZATION)).isFalse();
    }

    @Test
    void shouldAddIncomingValueWhenNotBlank() {
        var incomingHeaders = new MultivaluedHashMap<String, String>();
        incomingHeaders.putSingle(APM_PRINCIPAL_TOKEN, "valid-apm-token");

        var result = factory.update(incomingHeaders, new MultivaluedHashMap<>());

        assertThat(result.getFirst(APM_PRINCIPAL_TOKEN)).isEqualTo("valid-apm-token");
    }

    @Test
    void shouldNotAddHeaderWhenIncomingIsNullAndFallbackIsBlank() {
        var result = factory.update(new MultivaluedHashMap<>(), new MultivaluedHashMap<>());

        assertThat(result.containsKey(APM_PRINCIPAL_TOKEN)).isFalse();
        assertThat(result.containsKey(USER_AUTHORIZATION)).isFalse();
    }

    @Test
    void shouldUseFallbackWhenIncomingIsNullButFallbackIsValid() {
        try (var _ = ApmPrincipalTokenContext.withToken("fallback-apm-token")) {
            var incomingHeaders = new MultivaluedHashMap<String, String>();

            var result = factory.update(incomingHeaders, new MultivaluedHashMap<>());

            assertThat(result.getFirst(APM_PRINCIPAL_TOKEN)).isEqualTo("fallback-apm-token");
        }
    }

    @Test
    void shouldUseFallbackWhenIncomingIsBlankButFallbackIsValid() {
        try (var _ = AiServiceUserAuthorizationContext.withHeader("Bearer fallback-user")) {
            var incomingHeaders = new MultivaluedHashMap<String, String>();
            incomingHeaders.putSingle(USER_AUTHORIZATION, "   ");

            var result = factory.update(incomingHeaders, new MultivaluedHashMap<>());

            assertThat(result.getFirst(USER_AUTHORIZATION))
                    .isEqualTo("Bearer fallback-user");
        }
    }

    @Test
    void shouldNotAddHeaderWhenBothIncomingAndFallbackAreBlank() {
        var incomingHeaders = new MultivaluedHashMap<String, String>();
        incomingHeaders.putSingle(APM_PRINCIPAL_TOKEN, "   ");

        var result = factory.update(incomingHeaders, new MultivaluedHashMap<>());

        assertThat(result.containsKey(APM_PRINCIPAL_TOKEN)).isFalse();
    }

    @Test
    void shouldHandleEmptyHeadersList() {
        var incomingHeaders = new MultivaluedHashMap<String, String>();
        incomingHeaders.put(APM_PRINCIPAL_TOKEN, new ArrayList<>());

        var result = factory.update(incomingHeaders, new MultivaluedHashMap<>());

        assertThat(result.containsKey(APM_PRINCIPAL_TOKEN)).isFalse();
    }

    @Test
    void shouldNotAddHeaderWhenFallbackIsBlankString() throws Exception {
        var holder = getThreadLocal(ApmPrincipalTokenContext.class, "HOLDER");
        holder.set("   ");
        try {
            var result = factory.update(new MultivaluedHashMap<>(), new MultivaluedHashMap<>());
            assertThat(result.containsKey(APM_PRINCIPAL_TOKEN)).isFalse();
        } finally {
            holder.remove();
        }
    }

    @SuppressWarnings("unchecked")
    private static ThreadLocal<String> getThreadLocal(Class<?> owner, String fieldName) throws Exception {
        Field field = owner.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (ThreadLocal<String>) field.get(null);
    }
}
