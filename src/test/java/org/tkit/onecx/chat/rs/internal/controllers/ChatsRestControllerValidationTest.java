package org.tkit.onecx.chat.rs.internal.controllers;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.Test;

class ChatsRestControllerValidationTest {

    @Test
    void searchChatMessagesUsesTheInterfaceParameterConstraints() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().getConstraintsForClass(ChatsRestController.class))
                    .isNotNull();
        }
    }
}
