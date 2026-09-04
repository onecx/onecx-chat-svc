package org.tkit.onecx.chat.rs.internal.clients;

import java.util.List;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;

import org.eclipse.microprofile.rest.client.ext.ClientHeadersFactory;

public class AiServiceClientHeadersFactory implements ClientHeadersFactory {

    public static final String USER_AUTHORIZATION_HEADER_NAME = "UserAuthorization";

    @Override
    public MultivaluedMap<String, String> update(MultivaluedMap<String, String> incomingHeaders,
            MultivaluedMap<String, String> clientOutgoingHeaders) {
        var headers = new MultivaluedHashMap<String, String>();
        headers.putAll(clientOutgoingHeaders);

        addIfMissing(headers, ApmPrincipalTokenContext.HEADER_NAME,
                firstValue(incomingHeaders, ApmPrincipalTokenContext.HEADER_NAME),
                ApmPrincipalTokenContext.getToken());
        addIfMissing(headers, USER_AUTHORIZATION_HEADER_NAME,
                firstValue(incomingHeaders, USER_AUTHORIZATION_HEADER_NAME),
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
