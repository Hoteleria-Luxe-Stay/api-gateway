package com.hotel.gateway.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotencyGlobalFilterTest {

    @Mock
    private ReactiveStringRedisTemplate redis;

    @Mock
    @SuppressWarnings("rawtypes")
    private ReactiveValueOperations valueOps;

    @Mock
    private GatewayFilterChain chain;

    private ObjectMapper objectMapper;

    @InjectMocks
    private IdempotencyGlobalFilter filter;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        ReflectionTestUtils.setField(filter, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(filter, "ttlHours", 24L);
        ReflectionTestUtils.setField(filter, "protectedPathsConfig",
                List.of("/api/v1/reservas", "/api/v1/reservas/*/iniciar-pago"));
        filter.compilePatterns();

        lenient().when(redis.opsForValue()).thenReturn(valueOps);
    }

    // ==================== compilePatterns ====================

    @Test
    void compilePatternsHandlesNullProtectedPathsConfig() {
        IdempotencyGlobalFilter freshFilter = new IdempotencyGlobalFilter(redis, objectMapper);
        ReflectionTestUtils.setField(freshFilter, "protectedPathsConfig", null);

        // Should not throw
        freshFilter.compilePatterns();
    }

    @Test
    void compilePatternsIgnoresBlankAndNullEntries() {
        IdempotencyGlobalFilter freshFilter = new IdempotencyGlobalFilter(redis, objectMapper);
        ReflectionTestUtils.setField(freshFilter, "protectedPathsConfig",
                java.util.Arrays.asList("/api/v1/reservas", "", "  ", null, "/api/v1/pagos"));

        freshFilter.compilePatterns();

        // No exception expected; null/blank entries skipped silently
    }

    // ==================== getOrder ====================

    @Test
    void getOrderReturnsHighestPrecedencePlus50() {
        assertThat(filter.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 50);
    }

    // ==================== filter — pass-through cases ====================

    @Test
    void filterPassesThroughForNonPostMethod() {
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/reservas"));
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(chain).filter(exchange);
        verify(valueOps, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void filterPassesThroughForUnprotectedPath() {
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/hoteles"));
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(chain).filter(exchange);
        verify(valueOps, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    // ==================== filter — validation cases ====================

    @Test
    void filterReturns400WhenIdempotencyKeyHeaderMissing() {
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/reservas"));

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(chain, never()).filter(any());
    }

    @Test
    void filterReturns400WhenIdempotencyKeyIsBlank() {
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/reservas")
                        .header("Idempotency-Key", "   "));

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void filterReturns400WhenIdempotencyKeyIsNotValidUuid() {
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/reservas")
                        .header("Idempotency-Key", "not-a-uuid"));

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ==================== filter — lock acquired (first request) ====================

    @Test
    void filterAcquiresLockAndDelegatesToChainOnFirstRequest() {
        String uuid = "11111111-2222-3333-4444-555555555555";
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/reservas")
                        .header("Idempotency-Key", uuid));

        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(Mono.just(true));
        when(chain.filter(any(ServerWebExchange.class))).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain).contextWrite(jwtContext("123")))
                .verifyComplete();

        verify(valueOps).setIfAbsent(anyString(), anyString(), any(Duration.class));
        verify(chain).filter(any(ServerWebExchange.class));
    }

    // ==================== filter — replay cached ====================

    @Test
    void filterReplaysCachedResponseWhenLockAlreadyHeldAndCached() throws JsonProcessingException {
        String uuid = "22222222-2222-3333-4444-555555555555";
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/reservas")
                        .header("Idempotency-Key", uuid));

        CachedResponse cached = CachedResponse.of(201, "application/json",
                "{\"reservaId\":42}".getBytes());
        String json = objectMapper.writeValueAsString(cached);

        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(Mono.just(false));
        when(valueOps.get(anyString())).thenReturn(Mono.just(json));

        StepVerifier.create(filter.filter(exchange, chain).contextWrite(jwtContext("123")))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode())
                .isIn(HttpStatus.CREATED, HttpStatus.OK);
        assertThat(exchange.getResponse().getHeaders().getFirst("X-Idempotent-Replay"))
                .isEqualTo("true");
        verify(chain, never()).filter(any());
    }

    // ==================== filter — in-flight conflict ====================

    @Test
    void filterReturns409WhenAnotherRequestStillInFlight() {
        String uuid = "33333333-2222-3333-4444-555555555555";
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/reservas")
                        .header("Idempotency-Key", uuid));

        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(Mono.just(false));
        when(valueOps.get(anyString())).thenReturn(Mono.just("__IN_FLIGHT__"));

        StepVerifier.create(filter.filter(exchange, chain).contextWrite(jwtContext("123")))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        verify(chain, never()).filter(any());
    }

    @Test
    void filterReturns409WhenCachedResponseExpired() {
        String uuid = "44444444-2222-3333-4444-555555555555";
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/reservas")
                        .header("Idempotency-Key", uuid));

        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(Mono.just(false));
        when(valueOps.get(anyString())).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain).contextWrite(jwtContext("123")))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // ==================== filter — falls back to anonymous when no JWT ====================

    @Test
    void filterUsesAnonymousUserIdWhenNoJwtInContext() {
        String uuid = "55555555-2222-3333-4444-555555555555";
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/reservas")
                        .header("Idempotency-Key", uuid));

        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(Mono.just(true));
        when(chain.filter(any(ServerWebExchange.class))).thenReturn(Mono.empty());

        // No security context → resolveUserId returns "anonymous"
        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(valueOps).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void filterUsesJwtSubjectWhenUserIdClaimMissing() {
        String uuid = "66666666-2222-3333-4444-555555555555";
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/reservas")
                        .header("Idempotency-Key", uuid));

        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(Mono.just(true));
        when(chain.filter(any(ServerWebExchange.class))).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain).contextWrite(jwtContextSubjectOnly("user@luxestay.com")))
                .verifyComplete();
    }

    // ==================== filter — exercise writeWith inner class (executeAndCache flow) ====================

    @Test
    void filterCachesResponseAfterDownstreamWritesBody() {
        String uuid = "88888888-2222-3333-4444-555555555555";
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/reservas")
                        .header("Idempotency-Key", uuid));

        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(Mono.just(true));
        when(valueOps.set(anyString(), anyString(), any(Duration.class)))
                .thenReturn(Mono.just(true));

        // Simulamos que el downstream escribe un body OK al decorador → triggers writeWith
        when(chain.filter(any(ServerWebExchange.class))).thenAnswer(invocation -> {
            ServerWebExchange decoratedExchange = invocation.getArgument(0);
            org.springframework.http.server.reactive.ServerHttpResponse response =
                    decoratedExchange.getResponse();
            response.setStatusCode(org.springframework.http.HttpStatus.CREATED);
            response.getHeaders().setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            org.springframework.core.io.buffer.DataBuffer buffer =
                    response.bufferFactory().wrap("{\"reservaId\":99}".getBytes());
            return response.writeWith(Mono.just(buffer));
        });

        StepVerifier.create(filter.filter(exchange, chain).contextWrite(jwtContext("123")))
                .verifyComplete();

        // El cache debe haberse escrito con el response capturado
        verify(valueOps).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void filterDeletesCacheWhenDownstreamReturns5xxError() {
        String uuid = "99999999-2222-3333-4444-555555555555";
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/reservas")
                        .header("Idempotency-Key", uuid));

        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(Mono.just(true));
        when(redis.delete(anyString())).thenReturn(Mono.just(1L));

        // Simulamos un downstream que falla con 500
        when(chain.filter(any(ServerWebExchange.class))).thenAnswer(invocation -> {
            ServerWebExchange decoratedExchange = invocation.getArgument(0);
            org.springframework.http.server.reactive.ServerHttpResponse response =
                    decoratedExchange.getResponse();
            response.setStatusCode(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR);
            org.springframework.core.io.buffer.DataBuffer buffer =
                    response.bufferFactory().wrap("{\"error\":\"oops\"}".getBytes());
            return response.writeWith(Mono.just(buffer));
        });

        StepVerifier.create(filter.filter(exchange, chain).contextWrite(jwtContext("123")))
                .verifyComplete();

        // Para 5xx eliminamos la entry para que el cliente pueda reintentar
        verify(redis).delete(anyString());
    }

    // ==================== filter — wildcards in protected paths ====================

    @Test
    void filterMatchesWildcardProtectedPath() {
        String uuid = "77777777-2222-3333-4444-555555555555";
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/reservas/42/iniciar-pago")
                        .header("Idempotency-Key", uuid));

        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(Mono.just(true));
        when(chain.filter(any(ServerWebExchange.class))).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain).contextWrite(jwtContext("123")))
                .verifyComplete();

        verify(valueOps).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    // ==================== Helpers ====================

    /**
     * Construye un Reactor context con JWT autenticado que tiene userId claim.
     */
    private static reactor.util.context.Context jwtContext(String userId) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("user@luxestay.com")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .claim("userId", userId)
                .build();
        Authentication auth = new JwtAuthenticationToken(jwt);
        SecurityContext securityContext = new SecurityContextImpl(auth);
        return reactor.util.context.Context.of(
                ReactiveSecurityContextHolder.withSecurityContext(Mono.just(securityContext)));
    }

    /**
     * JWT con solo subject (sin userId claim) — para forzar el fallback al subject.
     */
    private static reactor.util.context.Context jwtContextSubjectOnly(String subject) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .claim("scope", "read")
                .build();
        Authentication auth = new JwtAuthenticationToken(jwt);
        SecurityContext securityContext = new SecurityContextImpl(auth);
        return reactor.util.context.Context.of(
                ReactiveSecurityContextHolder.withSecurityContext(Mono.just(securityContext)));
    }
}
