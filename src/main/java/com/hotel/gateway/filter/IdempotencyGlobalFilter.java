package com.hotel.gateway.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Filtro global de Idempotency-Key.
 *
 * Contrato:
 *  - Cualquier request POST que matchee {@code idempotency.protected-paths} DEBE traer
 *    el header {@code Idempotency-Key} (UUID v4 recomendado).
 *  - El gateway construye la clave Redis {@code idem:{userId}:{uuid}} y hace SETNX con
 *    TTL configurable (default 24h).
 *  - Primera vez (SETNX = true): se ejecuta el request real, se captura status + body
 *    via {@link ServerHttpResponseDecorator} y se guarda como JSON en Redis bajo la
 *    misma clave (sobrescribiendo el placeholder del SETNX).
 *  - Hits repetidos:
 *      - Si Redis ya tiene la response cacheada → la replay completa al cliente.
 *      - Si solo tiene el placeholder (request en vuelo) → 409 Conflict.
 *
 * Por que aca y no en cada microservicio: protege a TODO el sistema sin que cada
 * servicio re-implemente la logica. Defensa en profundidad — si el cliente reintenta
 * /iniciar-pago por timeout de red, no cobramos dos veces a Stripe.
 *
 * Order: HIGHEST_PRECEDENCE + 50 → corre DESPUES del JWT (porque necesitamos userId)
 * pero ANTES de los filtros de routing.
 */
@Component
public class IdempotencyGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger LOGGER = LoggerFactory.getLogger(IdempotencyGlobalFilter.class);
    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private static final String PLACEHOLDER = "__IN_FLIGHT__";
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    );

    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Value("${idempotency.protected-paths:}")
    private List<String> protectedPathsConfig;

    @Value("${idempotency.ttl-hours:24}")
    private long ttlHours;

    private final List<PathPattern> compiledPatterns = new ArrayList<>();

    public IdempotencyGlobalFilter(ReactiveStringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void compilePatterns() {
        PathPatternParser parser = PathPatternParser.defaultInstance;
        if (protectedPathsConfig != null) {
            for (String raw : protectedPathsConfig) {
                if (raw != null && !raw.isBlank()) {
                    compiledPatterns.add(parser.parse(raw.trim()));
                }
            }
        }
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        if (!HttpMethod.POST.equals(request.getMethod()) || !isProtectedPath(request)) {
            return chain.filter(exchange);
        }

        String idempotencyKey = request.getHeaders().getFirst(IDEMPOTENCY_HEADER);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return reject(exchange, HttpStatus.BAD_REQUEST,
                    "Header 'Idempotency-Key' es requerido en este endpoint");
        }
        if (!UUID_PATTERN.matcher(idempotencyKey).matches()) {
            return reject(exchange, HttpStatus.BAD_REQUEST,
                    "Header 'Idempotency-Key' debe ser un UUID v4 valido");
        }

        return resolveUserId(exchange).flatMap(userId -> {
            String redisKey = "idem:" + userId + ":" + idempotencyKey;
            Duration ttl = Duration.ofHours(ttlHours);

            return redis.opsForValue()
                    .setIfAbsent(redisKey, PLACEHOLDER, ttl)
                    .flatMap(acquired -> {
                        if (Boolean.TRUE.equals(acquired)) {
                            return executeAndCache(exchange, chain, redisKey, ttl);
                        }
                        return replayCachedOrConflict(exchange, redisKey);
                    });
        });
    }

    private boolean isProtectedPath(ServerHttpRequest request) {
        if (compiledPatterns.isEmpty()) {
            return false;
        }
        for (PathPattern pattern : compiledPatterns) {
            if (pattern.matches(request.getPath().pathWithinApplication())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Extrae el userId del JWT. Si no hay JWT en el contexto (request anonimo en endpoint
     * publico que casualmente este en protected-paths) usamos "anonymous" para evitar NPE,
     * pero en la practica la SecurityConfig deberia haber rechazado antes con 401.
     */
    private Mono<String> resolveUserId(ServerWebExchange exchange) {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ctx.getAuthentication())
                .filter(auth -> auth instanceof JwtAuthenticationToken)
                .cast(JwtAuthenticationToken.class)
                .map(token -> {
                    Jwt jwt = token.getToken();
                    Object userIdClaim = jwt.getClaim("userId");
                    if (userIdClaim != null) {
                        return userIdClaim.toString();
                    }
                    return jwt.getSubject() != null ? jwt.getSubject() : "anonymous";
                })
                .defaultIfEmpty("anonymous");
    }

    /**
     * Primera ejecucion: decora la response, captura el body, lo guarda en Redis y
     * lo escribe al cliente sin haberlo bloqueado. Tambien aprovecha el zero-copy de
     * Spring (write-with sobre el Flux<DataBuffer> original).
     */
    private Mono<Void> executeAndCache(ServerWebExchange exchange, GatewayFilterChain chain,
                                       String redisKey, Duration ttl) {
        ServerHttpResponse originalResponse = exchange.getResponse();
        ServerHttpResponseDecorator decorated = new ServerHttpResponseDecorator(originalResponse) {
            @Override
            public Mono<Void> writeWith(org.reactivestreams.Publisher<? extends DataBuffer> body) {
                Flux<DataBuffer> upstream = Flux.from(body);
                return DataBufferUtils.join(upstream).flatMap(joined -> {
                    byte[] bytes = new byte[joined.readableByteCount()];
                    joined.read(bytes);
                    DataBufferUtils.release(joined);

                    int statusCode = originalResponse.getStatusCode() != null
                            ? originalResponse.getStatusCode().value()
                            : HttpStatus.OK.value();
                    String contentType = originalResponse.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);

                    Mono<Void> cacheOp = saveToCache(redisKey, statusCode, contentType, bytes, ttl);

                    DataBuffer toClient = originalResponse.bufferFactory().wrap(bytes);
                    return cacheOp.then(super.writeWith(Mono.just(toClient)));
                });
            }
        };

        return chain.filter(exchange.mutate().response(decorated).build());
    }

    private Mono<Void> saveToCache(String redisKey, int status, String contentType,
                                   byte[] body, Duration ttl) {
        // No cachear errores 5xx — son transitorios, dejar que el cliente reintente
        if (status >= 500) {
            return redis.delete(redisKey).then();
        }
        try {
            CachedResponse snapshot = CachedResponse.of(status, contentType, body);
            String json = objectMapper.writeValueAsString(snapshot);
            return redis.opsForValue().set(redisKey, json, ttl).then();
        } catch (JsonProcessingException e) {
            LOGGER.warn("No se pudo serializar response para cache idempotente: {}", e.getMessage());
            return redis.delete(redisKey).then();
        }
    }

    private Mono<Void> replayCachedOrConflict(ServerWebExchange exchange, String redisKey) {
        return redis.opsForValue().get(redisKey).flatMap(value -> {
            if (PLACEHOLDER.equals(value)) {
                return reject(exchange, HttpStatus.CONFLICT,
                        "Request en proceso con la misma Idempotency-Key. Reintenta en unos segundos.");
            }
            return writeCached(exchange, value);
        }).switchIfEmpty(reject(exchange, HttpStatus.CONFLICT,
                "Idempotency-Key registrada pero la response expiro. Genera una nueva clave."));
    }

    private Mono<Void> writeCached(ServerWebExchange exchange, String json) {
        try {
            CachedResponse snapshot = objectMapper.readValue(json, CachedResponse.class);
            ServerHttpResponse response = exchange.getResponse();
            response.setStatusCode(HttpStatus.valueOf(snapshot.status()));
            if (snapshot.contentType() != null) {
                response.getHeaders().set(HttpHeaders.CONTENT_TYPE, snapshot.contentType());
            }
            response.getHeaders().set("X-Idempotent-Replay", "true");
            byte[] body = snapshot.decodedBody();
            DataBuffer buffer = response.bufferFactory().wrap(body);
            return response.writeWith(Mono.just(buffer));
        } catch (JsonProcessingException e) {
            LOGGER.error("Cache idempotente corrupto en Redis: {}", e.getMessage());
            return reject(exchange, HttpStatus.INTERNAL_SERVER_ERROR, "Error leyendo cache idempotente");
        }
    }

    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = String.format("{\"status\":%d,\"error\":\"%s\",\"message\":\"%s\"}",
                status.value(), status.getReasonPhrase(), escape(message));
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    private String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public int getOrder() {
        // Despues de SecurityWebFilterChain (ya tenemos JWT) y antes de RouteToRequestUrlFilter (-1)
        return Ordered.HIGHEST_PRECEDENCE + 50;
    }
}
