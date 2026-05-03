package com.hotel.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

/**
 * Round 7 — KeyResolver beans para Rate Limiting.
 *
 * Spring Cloud Gateway tiene un filtro nativo {@code RequestRateLimiter} que usa
 * Redis (token bucket algorithm). Cada request "gasta" tokens del bucket del
 * cliente. Si no hay tokens → 429 Too Many Requests.
 *
 * El KeyResolver dice "quien es el cliente". Tenemos dos:
 *  - userKeyResolver: extrae userId del JWT cuando hay autenticacion. Si no hay
 *    JWT, cae al IP (igual cliente debe identificarse de alguna forma).
 *    Es el {@code @Primary} — usado por endpoints autenticados.
 *  - ipKeyResolver: extrae la IP remota (atras de un LB usar X-Forwarded-For).
 *    Usado en /auth/login, /auth/register, /auth/password/reset.
 *
 * Por que NO usar IP siempre: dos usuarios atras del mismo NAT/proxy comparten
 * IP — uno abusivo arrastraria al otro. Por eso para endpoints autenticados
 * preferimos userId.
 */
@Configuration
public class RateLimiterConfig {

    /**
     * KeyResolver default: prefiere userId del JWT, cae a IP si no hay auth.
     * Marcado @Primary porque RequestRateLimiter usa el bean default si no se
     * especifica uno por nombre.
     */
    @Bean
    @Primary
    public KeyResolver userKeyResolver() {
        return exchange -> ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ctx.getAuthentication())
                .filter(auth -> auth instanceof JwtAuthenticationToken)
                .cast(JwtAuthenticationToken.class)
                .map(token -> {
                    Jwt jwt = token.getToken();
                    Object userId = jwt.getClaim("userId");
                    if (userId != null) {
                        return "user:" + userId;
                    }
                    return "user:" + (jwt.getSubject() != null ? jwt.getSubject() : "anon");
                })
                .switchIfEmpty(Mono.fromSupplier(() -> "ip:" + remoteAddress(exchange)));
    }

    /**
     * KeyResolver explicito por IP. Usado en endpoints publicos donde no hay JWT
     * (login, register, password reset).
     *
     * Atras de un load balancer o reverse proxy, mirar el header X-Forwarded-For
     * en lugar del remoteAddress. Aca leemos ambos: prioridad al header, fallback
     * a remoteAddress.
     */
    @Bean("ipKeyResolver")
    public KeyResolver ipKeyResolver() {
        return exchange -> Mono.just("ip:" + remoteAddress(exchange));
    }

    private static String remoteAddress(org.springframework.web.server.ServerWebExchange exchange) {
        String xff = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            // X-Forwarded-For puede traer una lista "client, proxy1, proxy2" — el primero es el original
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        InetSocketAddress addr = exchange.getRequest().getRemoteAddress();
        return addr != null ? addr.getAddress().getHostAddress() : "unknown";
    }
}
