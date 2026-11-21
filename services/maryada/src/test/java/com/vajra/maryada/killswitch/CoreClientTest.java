package com.vajra.maryada.killswitch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class CoreClientTest {

    private MockRestServiceServer server;
    private CoreClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new CoreClient(builder, "http://core:8081", "core-token");
    }

    @Test
    void killSendsTokenAndReason() {
        server.expect(requestTo("http://core:8081/internal/v1/kill"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer core-token"))
                .andExpect(content().json("{\"reason\":\"panic\"}"))
                .andRespond(withSuccess("{\"cancelledOrders\":1,\"flattenOrders\":2,\"flattened\":true}",
                        MediaType.APPLICATION_JSON));

        client.kill("panic");

        server.verify();
    }

    @Test
    void killParsesResult() {
        server.expect(requestTo("http://core:8081/internal/v1/kill"))
                .andRespond(withSuccess("{\"cancelledOrders\":3,\"flattenOrders\":2,\"flattened\":false}",
                        MediaType.APPLICATION_JSON));

        CoreClient.KillResult result = client.kill("x");

        assertThat(result.cancelledOrders()).isEqualTo(3);
        assertThat(result.flattenOrders()).isEqualTo(2);
        assertThat(result.flattened()).isFalse();
    }

    @Test
    void serverErrorThrowsCoreUnavailable() {
        server.expect(requestTo("http://core:8081/internal/v1/kill")).andRespond(withServerError());
        server.expect(requestTo("http://core:8081/internal/v1/resume")).andRespond(withServerError());

        assertThatThrownBy(() -> client.kill("x")).isInstanceOf(CoreUnavailableException.class);
        assertThatThrownBy(() -> client.resume()).isInstanceOf(CoreUnavailableException.class);
    }
}
