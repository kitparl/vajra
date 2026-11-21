package com.vajra.common;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class RequiredEnvironmentTest {

    @Test
    void namesEveryMissingVariable() {
        MockEnvironment env = new MockEnvironment().withProperty("DB_URL", "jdbc:postgresql://db/vajra");

        assertThatThrownBy(() -> RequiredEnvironment.check(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Missing required environment variable(s): DB_USER, DB_PASSWORD");
    }

    @Test
    void blankCountsAsMissing() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("DB_URL", "jdbc:postgresql://db/vajra")
                .withProperty("DB_USER", "core_svc")
                .withProperty("DB_PASSWORD", "  ");

        assertThatThrownBy(() -> RequiredEnvironment.check(env)).hasMessageEndingWith("DB_PASSWORD");
    }

    @Test
    void passesWhenAllPresent() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("DB_URL", "jdbc:postgresql://db/vajra")
                .withProperty("DB_USER", "core_svc")
                .withProperty("DB_PASSWORD", "secret");

        assertThatCode(() -> RequiredEnvironment.check(env)).doesNotThrowAnyException();
    }
}
