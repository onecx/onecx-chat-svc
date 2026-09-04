package org.tkit.onecx.chat.rs.internal.services;

import gen.io.github.onecx.ai.clients.model.RequestContext;

public record AsyncAiProcessingRequest(String chatId, String messageId, RequestContext context,
        String apmPrincipalToken, String userAuthorization) {
}
