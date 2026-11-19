package org.tkit.onecx.chat.rs.internal.clients;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.ext.ClientHeadersFactory;

@ApplicationScoped
public class ChatClientHeadersFactory implements ClientHeadersFactory {

    @ConfigProperty(name = "tkit.rs.context.token.header-param")
    String principalTokenHeader;

    @ConfigProperty(name = "onecx.user-authorization-header")
    String userAuthorizationHeader;

    @Override
    public MultivaluedMap<String, String> update(MultivaluedMap<String, String> incomingHeaders,
            MultivaluedMap<String, String> clientOutgoingHeaders) {
        var headers = new MultivaluedHashMap<String, String>();
        headers.putAll(clientOutgoingHeaders);

        addIfMissing(headers, principalTokenHeader,
                firstValue(incomingHeaders, principalTokenHeader),
                ApmPrincipalTokenContext.getToken());
        addIfMissing(headers, userAuthorizationHeader,
                firstValue(incomingHeaders, userAuthorizationHeader),
                AiServiceUserAuthorizationContext.getHeader());

        return headers;
    }

    private static void addIfMissing(MultivaluedMap<String, String> headers, String headerName,
            String incomingValue, String fallbackValue) {
        if (headers.containsKey(headerName)) {
            return;
        }
        var value = incomingValue;
        if (value == null || value.isBlank()) {
            value = fallbackValue;
        }
        if (value != null && !value.isBlank()) {
            headers.putSingle(headerName, value);
        }
    }

    private static String firstValue(MultivaluedMap<String, String> headers, String name) {
        if (headers == null) {
            return null;
        }
        List<String> values = headers.get(name);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }
}
