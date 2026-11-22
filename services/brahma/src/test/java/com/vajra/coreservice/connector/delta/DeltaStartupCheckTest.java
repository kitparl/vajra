package com.vajra.coreservice.connector.delta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DeltaStartupCheckTest {

    private final DeltaPrivateClient client = mock(DeltaPrivateClient.class);
    private final DeltaCredentials creds = new DeltaCredentials("fake-key", "fake-secret");

    private static DeltaApiException failure(String code, int status, boolean retryable) {
        return new DeltaApiException("Delta call failed: " + code, false, code, retryable, status, null);
    }

    @Test
    void missingCredentialsFailStartup() {
        DeltaStartupCheck check = new DeltaStartupCheck(new DeltaCredentials("", ""), client);

        assertThatThrownBy(() -> check.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DELTA_TESTNET_API_KEY/SECRET required for TRADING_MODE=DELTA_TESTNET");
        verify(client, never()).authCheck();
    }

    @Test
    void signatureExpiredMeansClockSkew() {
        doThrow(failure("SignatureExpired", 401, false)).when(client).authCheck();

        assertThatThrownBy(() -> new DeltaStartupCheck(creds, client).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("clock skew");
    }

    @Test
    void invalidApiKeyFailsStartup() {
        doThrow(failure("invalid_api_key", 401, false)).when(client).authCheck();

        assertThatThrownBy(() -> new DeltaStartupCheck(creds, client).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid testnet API key");
    }

    @Test
    void deltaDownOnlyWarns() {
        doThrow(failure("HTTP_503", 503, true)).when(client).authCheck();

        assertThatCode(() -> new DeltaStartupCheck(creds, client).run(null)).doesNotThrowAnyException();
    }

    @Test
    void healthyAuthCheckPasses() {
        assertThatCode(() -> new DeltaStartupCheck(creds, client).run(null)).doesNotThrowAnyException();
        verify(client).authCheck();
    }

    @Test
    void checkIsOnlyActiveInDeltaTestnetMode() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withBean(DeltaCredentials.class, () -> creds)
                .withBean(DeltaPrivateClient.class, () -> client)
                .withUserConfiguration(DeltaStartupCheck.class);

        runner.withPropertyValues("vajra.trading.mode=PAPER")
                .run(context -> assertThat(context).doesNotHaveBean(DeltaStartupCheck.class));
        runner.run(context -> assertThat(context).doesNotHaveBean(DeltaStartupCheck.class));
        runner.withPropertyValues("vajra.trading.mode=DELTA_TESTNET")
                .run(context -> assertThat(context).hasSingleBean(DeltaStartupCheck.class));
    }
}
