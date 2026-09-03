package org.tkit.onecx.chat.rs.internal.clients;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class AiServiceUserAuthorizationContextTest {

    @Test
    void shouldSetAndRetrieveHeader() {
        try (var _ = AiServiceUserAuthorizationContext.withHeader("Bearer test-token")) {
            assertThat(AiServiceUserAuthorizationContext.getHeader()).isEqualTo("Bearer test-token");
        }
    }

    @Test
    void shouldRestorePreviousHeaderAfterScope() {
        try (var _ = AiServiceUserAuthorizationContext.withHeader("Bearer first-token")) {
            assertThat(AiServiceUserAuthorizationContext.getHeader()).isEqualTo("Bearer first-token");

            try (var _ = AiServiceUserAuthorizationContext.withHeader("Bearer second-token")) {
                assertThat(AiServiceUserAuthorizationContext.getHeader()).isEqualTo("Bearer second-token");
            }

            assertThat(AiServiceUserAuthorizationContext.getHeader()).isEqualTo("Bearer first-token");
        }
    }

    @Test
    void shouldClearHeaderWhenNullIsSet() {
        try (var _ = AiServiceUserAuthorizationContext.withHeader("Bearer test-token")) {
            assertThat(AiServiceUserAuthorizationContext.getHeader()).isEqualTo("Bearer test-token");

            try (var _ = AiServiceUserAuthorizationContext.withHeader(null)) {
                assertThat(AiServiceUserAuthorizationContext.getHeader()).isNull();
            }

            assertThat(AiServiceUserAuthorizationContext.getHeader()).isEqualTo("Bearer test-token");
        }
    }

    @Test
    void shouldClearHeaderWhenBlankIsSet() {
        try (var _ = AiServiceUserAuthorizationContext.withHeader("Bearer test-token")) {
            assertThat(AiServiceUserAuthorizationContext.getHeader()).isEqualTo("Bearer test-token");

            try (var _ = AiServiceUserAuthorizationContext.withHeader("   ")) {
                assertThat(AiServiceUserAuthorizationContext.getHeader()).isNull();
            }

            assertThat(AiServiceUserAuthorizationContext.getHeader()).isEqualTo("Bearer test-token");
        }
    }

    @Test
    void shouldRemoveHeaderWhenScopeEnds() {
        try (var _ = AiServiceUserAuthorizationContext.withHeader("Bearer test-token")) {
            assertThat(AiServiceUserAuthorizationContext.getHeader()).isEqualTo("Bearer test-token");
        }

        assertThat(AiServiceUserAuthorizationContext.getHeader()).isNull();
    }

    @Test
    void shouldHandleNestedScopesWithNullPrevious() {
        assertThat(AiServiceUserAuthorizationContext.getHeader()).isNull();

        try (var _ = AiServiceUserAuthorizationContext.withHeader("Bearer test-token")) {
            assertThat(AiServiceUserAuthorizationContext.getHeader()).isEqualTo("Bearer test-token");

            try (var _ = AiServiceUserAuthorizationContext.withHeader(null)) {
                assertThat(AiServiceUserAuthorizationContext.getHeader()).isNull();
            }

            assertThat(AiServiceUserAuthorizationContext.getHeader()).isEqualTo("Bearer test-token");
        }

        assertThat(AiServiceUserAuthorizationContext.getHeader()).isNull();
    }

    @Test
    void shouldRestoreNonBlankPreviousHeaderWhenExitingInnerScope() {
        try (var _ = AiServiceUserAuthorizationContext.withHeader("Bearer outer-token")) {
            assertThat(AiServiceUserAuthorizationContext.getHeader()).isEqualTo("Bearer outer-token");

            try (var _ = AiServiceUserAuthorizationContext.withHeader("Bearer inner-token")) {
                assertThat(AiServiceUserAuthorizationContext.getHeader()).isEqualTo("Bearer inner-token");
            }

            assertThat(AiServiceUserAuthorizationContext.getHeader()).isEqualTo("Bearer outer-token");
        }
    }

    @Test
    void shouldRestoreBlankPreviousHeaderByRemoving() {
        try (var _ = AiServiceUserAuthorizationContext.withHeader("   ")) {
            assertThat(AiServiceUserAuthorizationContext.getHeader()).isNull();

            try (var _ = AiServiceUserAuthorizationContext.withHeader("Bearer inner-token")) {
                assertThat(AiServiceUserAuthorizationContext.getHeader()).isEqualTo("Bearer inner-token");
            }

            assertThat(AiServiceUserAuthorizationContext.getHeader()).isNull();
        }
    }

    @Test
    void shouldHandleMultipleNestedScopes() {
        try (var _ = AiServiceUserAuthorizationContext.withHeader("Bearer level1")) {
            assertThat(AiServiceUserAuthorizationContext.getHeader()).isEqualTo("Bearer level1");

            try (var _ = AiServiceUserAuthorizationContext.withHeader("Bearer level2")) {
                assertThat(AiServiceUserAuthorizationContext.getHeader()).isEqualTo("Bearer level2");

                try (var _ = AiServiceUserAuthorizationContext.withHeader("Bearer level3")) {
                    assertThat(AiServiceUserAuthorizationContext.getHeader()).isEqualTo("Bearer level3");
                }

                assertThat(AiServiceUserAuthorizationContext.getHeader()).isEqualTo("Bearer level2");
            }

            assertThat(AiServiceUserAuthorizationContext.getHeader()).isEqualTo("Bearer level1");
        }

        assertThat(AiServiceUserAuthorizationContext.getHeader()).isNull();
    }

    @Test
    void shouldRemoveWhenPreviousHeaderIsBlank() throws Exception {
        var holder = getThreadLocal(AiServiceUserAuthorizationContext.class, "HOLDER");
        holder.set("   ");
        try {
            try (var _ = AiServiceUserAuthorizationContext.withHeader("Bearer inner")) {
                assertThat(AiServiceUserAuthorizationContext.getHeader()).isEqualTo("Bearer inner");
            }
            assertThat(AiServiceUserAuthorizationContext.getHeader()).isNull();
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
