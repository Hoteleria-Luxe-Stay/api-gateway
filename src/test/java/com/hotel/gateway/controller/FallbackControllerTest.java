package com.hotel.gateway.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FallbackControllerTest {

    private final FallbackController controller = new FallbackController();

    @Test
    void fallbackGetReturns503WithServiceName() {
        Mono<ResponseEntity<Map<String, Object>>> result = controller.fallbackGet("auth-service");

        StepVerifier.create(result)
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(response.getBody()).isNotNull();
                    assertThat(response.getBody()).containsEntry("service", "auth-service");
                    assertThat(response.getBody()).containsEntry("status", 503);
                    assertThat(response.getBody()).containsEntry("error", "Service Unavailable");
                    assertThat(response.getBody().get("message").toString())
                            .contains("auth-service")
                            .contains("no esta disponible");
                    assertThat(response.getBody()).containsKey("timestamp");
                })
                .verifyComplete();
    }

    @Test
    void fallbackPostReturns503WithServiceName() {
        Mono<ResponseEntity<Map<String, Object>>> result = controller.fallbackPost("hotel-service");

        StepVerifier.create(result)
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(response.getBody()).containsEntry("service", "hotel-service");
                })
                .verifyComplete();
    }

    @Test
    void fallbackBuildsResponseForAnyServiceName() {
        Mono<ResponseEntity<Map<String, Object>>> result = controller.fallbackGet("reserva-service");

        StepVerifier.create(result)
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(response.getBody().get("service")).isEqualTo("reserva-service");
                    assertThat(response.getBody().get("message").toString())
                            .contains("reserva-service");
                })
                .verifyComplete();
    }
}
