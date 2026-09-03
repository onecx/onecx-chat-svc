package org.tkit.onecx.chat.rs.internal.clients;

public final class ApmPrincipalTokenContext {

    public static final String HEADER_NAME = "apm-principal-token";

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private ApmPrincipalTokenContext() {
    }

    public static Scope withToken(String token) {
        var previous = HOLDER.get();
        if (token == null || token.isBlank()) {
            HOLDER.remove();
        } else {
            HOLDER.set(token);
        }
        return () -> {
            if (previous == null || previous.isBlank()) {
                HOLDER.remove();
            } else {
                HOLDER.set(previous);
            }
        };
    }

    public static String getToken() {
        return HOLDER.get();
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {

        @Override
        void close();
    }
}
