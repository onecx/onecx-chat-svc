package org.tkit.onecx.chat.rs.internal.clients;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class ApmPrincipalTokenContextTest {

    @Test
    void shouldSetAndRetrieveToken() {
        try (var _ = ApmPrincipalTokenContext.withToken("test-token")) {
            assertThat(ApmPrincipalTokenContext.getToken()).isEqualTo("test-token");
        }
    }

    @Test
    void shouldRestorePreviousTokenAfterScope() {
        try (var _ = ApmPrincipalTokenContext.withToken("first-token")) {
            assertThat(ApmPrincipalTokenContext.getToken()).isEqualTo("first-token");

            try (var _ = ApmPrincipalTokenContext.withToken("second-token")) {
                assertThat(ApmPrincipalTokenContext.getToken()).isEqualTo("second-token");
            }

            assertThat(ApmPrincipalTokenContext.getToken()).isEqualTo("first-token");
        }
    }

    @Test
    void shouldClearTokenWhenNullIsSet() {
        try (var _ = ApmPrincipalTokenContext.withToken("test-token")) {
            assertThat(ApmPrincipalTokenContext.getToken()).isEqualTo("test-token");

            try (var _ = ApmPrincipalTokenContext.withToken(null)) {
                assertThat(ApmPrincipalTokenContext.getToken()).isNull();
            }

            assertThat(ApmPrincipalTokenContext.getToken()).isEqualTo("test-token");
        }
    }

    @Test
    void shouldClearTokenWhenBlankIsSet() {
        try (var _ = ApmPrincipalTokenContext.withToken("test-token")) {
            assertThat(ApmPrincipalTokenContext.getToken()).isEqualTo("test-token");

            try (var _ = ApmPrincipalTokenContext.withToken("   ")) {
                assertThat(ApmPrincipalTokenContext.getToken()).isNull();
            }

            assertThat(ApmPrincipalTokenContext.getToken()).isEqualTo("test-token");
        }
    }

    @Test
    void shouldRemoveTokenWhenScopeEnds() {
        try (var _ = ApmPrincipalTokenContext.withToken("test-token")) {
            assertThat(ApmPrincipalTokenContext.getToken()).isEqualTo("test-token");
        }

        assertThat(ApmPrincipalTokenContext.getToken()).isNull();
    }

    @Test
    void shouldHandleNestedScopesWithNullPrevious() {
        assertThat(ApmPrincipalTokenContext.getToken()).isNull();

        try (var _ = ApmPrincipalTokenContext.withToken("test-token")) {
            assertThat(ApmPrincipalTokenContext.getToken()).isEqualTo("test-token");

            try (var _ = ApmPrincipalTokenContext.withToken(null)) {
                assertThat(ApmPrincipalTokenContext.getToken()).isNull();
            }

            assertThat(ApmPrincipalTokenContext.getToken()).isEqualTo("test-token");
        }

        assertThat(ApmPrincipalTokenContext.getToken()).isNull();
    }
}
