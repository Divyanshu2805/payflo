package com.project.payflo.common_lib.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private static String code(ResponseEntity<ErrorResponse> response) {
        return response.getBody().errorCode();
    }

    @Test
    void anUnknownPathIsA404NotA500() {
        ResponseEntity<ErrorResponse> response = handler.handleNoRoute(new NoResourceFoundException(HttpMethod.GET, "/v1/nope", "v1/nope"));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(code(response)).isEqualTo("ROUTE_NOT_FOUND");
    }

    @Test
    void aWrongMethodIsA405ThatSaysWhatIsAllowed() {
        ResponseEntity<ErrorResponse> response = handler.handleMethodNotAllowed(
                new HttpRequestMethodNotSupportedException("GET", List.of("POST")));

        assertThat(response.getStatusCode().value()).isEqualTo(405);
        assertThat(code(response)).isEqualTo("METHOD_NOT_ALLOWED");
        assertThat(response.getHeaders().getFirst(HttpHeaders.ALLOW)).contains("POST");
    }

    @Test
    void anUnsupportedContentTypeIsA415() {
        ResponseEntity<ErrorResponse> response = handler.handleUnsupportedMediaType(
                new HttpMediaTypeNotSupportedException(MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON)));

        assertThat(response.getStatusCode().value()).isEqualTo(415);
        assertThat(code(response)).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    void aMissingRequestParameterIsA400() {
        ResponseEntity<ErrorResponse> response = handler.handleMissingParameter(
                new MissingServletRequestParameterException("status", "String"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(code(response)).isEqualTo("MISSING_PARAMETER");
    }

    @Test
    void aForbiddenCallerIsA403WithItsOwnCode() {
        ResponseEntity<ErrorResponse> response = handler.handleForbidden(
                new ForbiddenException("ROLE_FORBIDDEN", "Your role cannot do this"));

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(code(response)).isEqualTo("ROLE_FORBIDDEN");
    }

    @Test
    void anInvalidRefreshTokenIsA401() {
        ResponseEntity<ErrorResponse> response = handler.handleInvalidToken(new InvalidTokenException());

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(code(response)).isEqualTo("INVALID_REFRESH_TOKEN");
    }

    @Test
    void anUnavailableDependencyIsA503WithItsOwnCode() {
        ResponseEntity<ErrorResponse> response = handler.handleServiceUnavailable(
                new ServiceUnavailableException("AUDIT_LOG_UNAVAILABLE", "try again shortly"));

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(code(response)).isEqualTo("AUDIT_LOG_UNAVAILABLE");
    }

    @Test
    void aVelocityLimitIsA429WithRetryAfterAndACodeThatIsNotTheOrdinaryRateLimit() {
        ResponseEntity<ErrorResponse> response = handler.handleVelocityLimit(
                new VelocityLimitException("CARD_TESTING_SUSPECTED", "too many declines", 540));

        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("540");
        assertThat(code(response)).isEqualTo("CARD_TESTING_SUSPECTED").isNotEqualTo("RATE_LIMIT_EXCEEDED");
    }

    @Test
    void anythingElseIsStillA500WithNoDetails() {
        ResponseEntity<ErrorResponse> response = handler.handleUnexpected(
                new IllegalStateException("secret internal detail"), new org.springframework.mock.web.MockHttpServletRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().errorDescription()).doesNotContain("secret internal detail");
    }
}
