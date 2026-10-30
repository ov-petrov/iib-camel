package ru.petrovov.iib_camel.runtime_lite;

import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/unavailable",
    "spring.datasource.username=test",
    "spring.datasource.password=test",
    "spring.autoconfigure.exclude=org.springframework.boot.data.jdbc.autoconfigure.DataJdbcRepositoriesAutoConfiguration"
})
@ActiveProfiles("test")
class ActuatorProbesTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    @LocalServerPort
    private int port;

    @Autowired
    private ApplicationContext context;

    @MockitoBean
    private SpringLiquibase liquibase;

    @Test
    void probesReflectAvailabilityWithoutExternalInfrastructure() throws Exception {
        assertUp("/actuator/health/liveness");
        assertUp("/actuator/health/readiness");
        assertThat(get("/actuator/env").statusCode()).isEqualTo(404);

        try {
            AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);

            assertThat(get("/actuator/health/readiness").statusCode()).isEqualTo(503);
            assertUp("/actuator/health/liveness");
        } finally {
            AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
        }

        assertUp("/actuator/health/readiness");

        try {
            AvailabilityChangeEvent.publish(context, LivenessState.BROKEN);

            assertThat(get("/actuator/health/liveness").statusCode()).isEqualTo(503);
            assertUp("/actuator/health/readiness");
        } finally {
            AvailabilityChangeEvent.publish(context, LivenessState.CORRECT);
        }

        assertUp("/actuator/health/liveness");
    }

    private void assertUp(String path) throws Exception {
        var response = get(path);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    private HttpResponse<String> get(String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .timeout(TIMEOUT)
            .GET()
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
