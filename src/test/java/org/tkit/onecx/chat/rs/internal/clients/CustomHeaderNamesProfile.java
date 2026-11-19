package org.tkit.onecx.chat.rs.internal.clients;

import java.util.Map;

import io.quarkus.test.junit.QuarkusTestProfile;

public class CustomHeaderNamesProfile implements QuarkusTestProfile {

    public static final String PRINCIPAL_TOKEN_HEADER = "x-custom-principal-token";
    public static final String USER_AUTHORIZATION_HEADER = "x-custom-user-auth";

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "tkit.rs.context.token.header-param", PRINCIPAL_TOKEN_HEADER,
                "onecx.user-authorization-header", USER_AUTHORIZATION_HEADER);
    }
}
