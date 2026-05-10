package com.hotel.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CachedResponseTest {

    @Test
    void ofEncodesBodyToBase64() {
        byte[] body = "hello world".getBytes();

        CachedResponse snapshot = CachedResponse.of(200, "application/json", body);

        assertThat(snapshot.status()).isEqualTo(200);
        assertThat(snapshot.contentType()).isEqualTo("application/json");
        assertThat(snapshot.bodyBase64()).isNotEmpty();
        assertThat(snapshot.bodyBase64()).isNotEqualTo("hello world");
    }

    @Test
    void ofWithNullBodyReturnsEmptyBase64() {
        CachedResponse snapshot = CachedResponse.of(200, "application/json", null);

        assertThat(snapshot.bodyBase64()).isEmpty();
    }

    @Test
    void ofWithEmptyBodyReturnsEmptyBase64() {
        CachedResponse snapshot = CachedResponse.of(204, "application/json", new byte[0]);

        assertThat(snapshot.bodyBase64()).isEmpty();
    }

    @Test
    void decodedBodyDecodesBase64BackToOriginal() {
        byte[] original = "{\"message\":\"luxe-stay\"}".getBytes();
        CachedResponse snapshot = CachedResponse.of(200, "application/json", original);

        byte[] decoded = snapshot.decodedBody();

        assertThat(decoded).isEqualTo(original);
    }

    @Test
    void decodedBodyHandlesNullBase64() {
        CachedResponse snapshot = new CachedResponse(200, "application/json", null);

        byte[] decoded = snapshot.decodedBody();

        assertThat(decoded).isEmpty();
    }

    @Test
    void decodedBodyHandlesEmptyBase64() {
        CachedResponse snapshot = new CachedResponse(200, "application/json", "");

        byte[] decoded = snapshot.decodedBody();

        assertThat(decoded).isEmpty();
    }

    @Test
    void recordCanBeSerializedAndDeserializedViaJackson() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        CachedResponse original = CachedResponse.of(201, "application/json",
                "{\"id\":42}".getBytes());

        String json = mapper.writeValueAsString(original);
        CachedResponse roundtrip = mapper.readValue(json, CachedResponse.class);

        assertThat(roundtrip.status()).isEqualTo(201);
        assertThat(roundtrip.contentType()).isEqualTo("application/json");
        assertThat(roundtrip.decodedBody()).isEqualTo("{\"id\":42}".getBytes());
    }

    @Test
    void recordWithNullContentTypeIsValid() {
        CachedResponse snapshot = CachedResponse.of(204, null, new byte[]{1, 2, 3});

        assertThat(snapshot.contentType()).isNull();
        assertThat(snapshot.decodedBody()).containsExactly(1, 2, 3);
    }
}
