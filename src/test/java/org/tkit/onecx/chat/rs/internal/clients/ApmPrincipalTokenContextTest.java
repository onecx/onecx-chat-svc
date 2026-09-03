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

    @Test
    void shouldRestoreNonBlankPreviousTokenWhenExitingInnerScope() {
        try (var _ = ApmPrincipalTokenContext.withToken("outer-token")) {
            assertThat(ApmPrincipalTokenContext.getToken()).isEqualTo("outer-token");

            try (var _ = ApmPrincipalTokenContext.withToken("inner-token")) {
                assertThat(ApmPrincipalTokenContext.getToken()).isEqualTo("inner-token");
            }

            // This line exercises the else branch: if (previous != null && !previous.isBlank())
            assertThat(ApmPrincipalTokenContext.getToken()).isEqualTo("outer-token");
        }
    }

    @Test
    void shouldRestoreBlankPreviousTokenByRemoving() {
        try (var _ = ApmPrincipalTokenContext.withToken("   ")) {
            assertThat(ApmPrincipalTokenContext.getToken()).isNull();

            try (var _ = ApmPrincipalTokenContext.withToken("inner-token")) {
                assertThat(ApmPrincipalTokenContext.getToken()).isEqualTo("inner-token");
            }

            // This line exercises the if branch: if (previous == null || previous.isBlank())
            assertThat(ApmPrincipalTokenContext.getToken()).isNull();
        }
    }

    @Test
    void shouldHandleMultipleNestedScopes() {
        try (var _ = ApmPrincipalTokenContext.withToken("level1")) {
            assertThat(ApmPrincipalTokenContext.getToken()).isEqualTo("level1");

            try (var _ = ApmPrincipalTokenContext.withToken("level2")) {
                assertThat(ApmPrincipalTokenContext.getToken()).isEqualTo("level2");

                try (var _ = ApmPrincipalTokenContext.withToken("level3")) {
                    assertThat(ApmPrincipalTokenContext.getToken()).isEqualTo("level3");
                }

                assertThat(ApmPrincipalTokenContext.getToken()).isEqualTo("level2");
            }

            assertThat(ApmPrincipalTokenContext.getToken()).isEqualTo("level1");
        }

        assertThat(ApmPrincipalTokenContext.getToken()).isNull();
    }
}
