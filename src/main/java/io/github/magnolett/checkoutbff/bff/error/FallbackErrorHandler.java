package io.github.magnolett.checkoutbff.bff.error;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * Covers what happens outside a controller (unknown route, wrong HTTP method, unsupported media type), so even
 * those answers keep the error contract. Runs before Spring Boot's default handler (order -1).
 */
@Slf4j
@Component
@Order(-2)
@RequiredArgsConstructor
public class FallbackErrorHandler implements WebExceptionHandler {

    private final JsonMapper jsonMapper;

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.error(ex);
        }
        ErrorCode code;
        HttpStatusCode status;
        if (ex instanceof ResponseStatusException rse && rse.getStatusCode().is4xxClientError()) {
            code = ErrorCode.INVALID_REQUEST;
            status = rse.getStatusCode();
            log.info("request rejected before reaching a controller status={} path={}", status,
                    exchange.getRequest().getPath());
        } else {
            code = ErrorCode.INTERNAL_ERROR;
            status = code.status();
            log.error("unexpected failure outside controllers path={}", exchange.getRequest().getPath(), ex);
        }
        byte[] body = jsonMapper.writeValueAsBytes(code.toResponse(null));
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }
}
