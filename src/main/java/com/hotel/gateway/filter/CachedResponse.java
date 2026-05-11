package com.hotel.gateway.filter;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Base64;

/**
 * Snapshot serializablee de la response a cachear en Redis.
 * El body se guarda en Base64 para soportar payloads binarios sin perder bytes.
 */
public record CachedResponse(
        @JsonProperty("status") int status,
        @JsonProperty("contentType") String contentType,
        @JsonProperty("bodyBase64") String bodyBase64
) {

    @JsonCreator
    public CachedResponse {
    }

    public static CachedResponse of(int status, String contentType, byte[] body) {
        String encoded = body == null ? "" : Base64.getEncoder().encodeToString(body);
        return new CachedResponse(status, contentType, encoded);
    }

    public byte[] decodedBody() {
        if (bodyBase64 == null || bodyBase64.isEmpty()) {
            return new byte[0];
        }
        return Base64.getDecoder().decode(bodyBase64);
    }
}
