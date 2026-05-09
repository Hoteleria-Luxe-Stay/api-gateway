package com.hotel.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;

import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Value("${application.security.jwt.public-key}")
    private String publicKeyPem;

    @Value("${application.security.jwt.issuer}")
    private String jwtIssuer;

    @Value("${application.security.jwt.audience}")
    private String jwtAudience;

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
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withPublicKey(publicKey).build();

        OAuth2TokenValidator<Jwt> withIssuer = JwtValidators.createDefaultWithIssuer(jwtIssuer);
        OAuth2TokenValidator<Jwt> withAudience = new JwtClaimValidator<List<String>>(
                JwtClaimNames.AUD,
                aud -> aud != null && aud.contains(jwtAudience)
        );
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(withIssuer, withAudience);
        decoder.setJwtValidator(validator);

        return decoder;
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        http
                // CORS: delegar al CORS de Spring Cloud Gateway (api-gateway.yml)
                // NO deshabilitarlo, para que los preflight OPTIONS funcionen
                .cors(cors -> { })
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        // Preflight CORS (Angular envia OPTIONS antes de cada request)
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // Rutas publicas (no requieren JWT)
                        .pathMatchers(
                                "/api/v1/auth/login",
                                "/api/v1/auth/register",
                                "/api/v1/auth/refresh",
                                "/api/v1/auth/validate",
                                "/api/v1/auth/password/**",
                                "/api/v1/contacto/**",
                                "/api/v1/oauth/token",
                                // Webhook publico del proveedor de pago — autenticidad validada por HMAC en ms-pago.
                                // SIN JWT: el proveedor no manda Bearer, manda firma propia (x-signature en MP).
                                "/api/v1/pagos/webhook/**",
                                "/actuator/**",
                                "/api/v1/actuator/**"
                        ).permitAll()
                        // Rutas publicas GET (consultar hoteles, habitaciones, departamentos, tipos)
                        .pathMatchers(HttpMethod.GET,
                                "/api/v1/hoteles",
                                "/api/v1/hoteles/**",
                                "/api/v1/departamentos",
                                "/api/v1/departamentos/**",
                                "/api/v1/habitaciones",
                                "/api/v1/habitaciones/**",
                                "/api/v1/tipos-habitacion",
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
