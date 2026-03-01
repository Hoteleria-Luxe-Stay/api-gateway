package com.hotel.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;

import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Value("${application.security.jwt.public-key}")
    private String publicKeyPem;

    @Bean
    public RSAPublicKey rsaPublicKey() throws Exception {
        String key = publicKeyPem
                .replace("\\r", "")
                .replace("\\n", "\n")
                .replace("\\", "")
                .replace("\r", "")
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] decoded = Base64.getDecoder().decode(key);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(decoded);
        return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(spec);
    }

    @Bean
    public ReactiveJwtDecoder reactiveJwtDecoder(RSAPublicKey publicKey) {
        return NimbusReactiveJwtDecoder.withPublicKey(publicKey).build();
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        http
                .cors(ServerHttpSecurity.CorsSpec::disable)
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        // Rutas publicas (no requieren JWT)
                        .pathMatchers(
                                "/api/v1/auth/login",
                                "/api/v1/auth/register",
                                "/api/v1/auth/refresh",
                                "/api/v1/auth/validate",
                                "/api/v1/auth/password/**",
                                "/api/v1/contacto/**",
                                "/api/v1/oauth/token",
                                "/actuator/**",
                                "/api/v1/actuator/**"
                        ).permitAll()
                        // Rutas publicas GET (consultar hoteles, habitaciones, departamentos, tipos)
                        .pathMatchers(org.springframework.http.HttpMethod.GET,
                                "/api/v1/hoteles/**",
                                "/api/v1/departamentos/**",
                                "/api/v1/habitaciones/**",
                                "/api/v1/tipos-habitacion/**"
                        ).permitAll()
                        // Todo lo demas requiere JWT valido
                        .anyExchange().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> { })
                );

        return http.build();
    }
}
