package com.hotel.gateway.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Fallback endpoint para el filtro CircuitBreaker de Spring Cloud Gateway.
 *
 * Cuando un backend service esta down (circuit OPEN), Gateway redirige aca
 * en lugar de fallar con 5xx desconocido. Devolvemos 503 Service Unavailable
 * con un payload uniforme que el frontend puede manejar limpiamente.
 *
 * Configuracion en api-gateway.yml:
 *   - name: CircuitBreaker
 *     args:
 *       name: cb-{service}
 *       fallbackUri: forward:/fallback/{service}
 */
@RestController
@RequestMapping("/fallback")
public class FallbackController {

    @GetMapping("/{service}")
    public Mono<ResponseEntity<Map<String, Object>>> fallbackGet(@PathVariable String service) {
        return Mono.just(buildResponse(service));
    }

    @PostMapping("/{service}")
    public Mono<ResponseEntity<Map<String, Object>>> fallbackPost(@PathVariable String service) {
        return Mono.just(buildResponse(service));
    }

    private ResponseEntity<Map<String, Object>> buildResponse(String service) {
        Map<String, Object> body = Map.of(
                "timestamp", OffsetDateTime.now().toString(),
                "status", HttpStatus.SERVICE_UNAVAILABLE.value(),
                "error", "Service Unavailable",
                "service", service,
                "message", "El servicio '" + service + "' no esta disponible temporalmente. " +
                        "Por favor, intenta nuevamente en unos segundos."
        );
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}
