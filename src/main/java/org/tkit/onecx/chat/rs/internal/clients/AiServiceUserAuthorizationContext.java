package org.tkit.onecx.chat.rs.internal.clients;

public final class AiServiceUserAuthorizationContext {

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private AiServiceUserAuthorizationContext() {
    }

    public static Scope withHeader(String userAuthorization) {
        var previous = HOLDER.get();
        if (userAuthorization == null || userAuthorization.isBlank()) {
            HOLDER.remove();
        } else {
            HOLDER.set(userAuthorization);
        }
        return () -> {
            if (previous == null || previous.isBlank()) {
                HOLDER.remove();
            } else {
                HOLDER.set(previous);
            }
        };
    }

    public static String getHeader() {
        return HOLDER.get();
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {

        @Override
        void close();
    }
}
