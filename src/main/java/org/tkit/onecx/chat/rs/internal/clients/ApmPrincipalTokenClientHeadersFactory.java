package org.tkit.onecx.chat.rs.internal.clients;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.ext.ClientHeadersFactory;

@ApplicationScoped
public class ApmPrincipalTokenClientHeadersFactory implements ClientHeadersFactory {

    @ConfigProperty(name = "tkit.rs.context.token.header-param")
    String principalTokenHeader;

    @Override
    public MultivaluedMap<String, String> update(MultivaluedMap<String, String> incomingHeaders,
            MultivaluedMap<String, String> clientOutgoingHeaders) {
        var headers = new MultivaluedHashMap<String, String>();
        headers.putAll(clientOutgoingHeaders);

        if (headers.containsKey(principalTokenHeader)) {
            return headers;
        }

        var token = getFirst(incomingHeaders, principalTokenHeader);
        if (token == null || token.isBlank()) {
            token = ApmPrincipalTokenContext.getToken();
        }

        if (token != null && !token.isBlank()) {
            headers.putSingle(principalTokenHeader, token);
        }

        return headers;
    }

    private static String getFirst(MultivaluedMap<String, String> headers, String name) {
        if (headers == null) {
            return null;
        }
        List<String> values = headers.get(name);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }
}
