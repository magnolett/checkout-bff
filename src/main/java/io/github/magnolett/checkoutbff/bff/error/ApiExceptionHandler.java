package io.github.magnolett.checkoutbff.bff.error;

import io.github.magnolett.checkoutbff.checkout.domain.CheckoutException;
import io.github.magnolett.checkoutbff.checkout.port.ServiceUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;

import java.util.UUID;

/**
 * Single translation point from exceptions to the error contract. Logs never carry request bodies or rejected
 * values: a validation message could echo a card number.
 */
@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    /** Exchange attribute with the cart id of the request, so a technical failure can still name the cart. */
    public static final String CART_ID_ATTRIBUTE = ApiExceptionHandler.class.getName() + ".cartId";

    @ExceptionHandler(CheckoutException.class)
    public ResponseEntity<ErrorResponse> handleBusinessFailure(CheckoutException ex) {
        ErrorCode code = ErrorCode.of(ex.error());
        log.info("business failure errorCode={} cartId={} detail={}", code, ex.cartId(), ex.getMessage());
        return code.toResponseEntity(ex.cartId());
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<ErrorResponse> handleBeanValidation(WebExchangeBindException ex) {
        log.info("invalid request fields={}", ex.getFieldErrors().stream().map(FieldError::getField).toList());
        return ErrorCode.INVALID_REQUEST.toResponseEntity(null);
    }

    @ExceptionHandler(ServerWebInputException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableInput(ServerWebInputException ex) {
        log.info("unreadable request reason={}", ex.getReason());
        return ErrorCode.INVALID_REQUEST.toResponseEntity(null);
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRequest(InvalidRequestException ex, ServerWebExchange exchange) {
        log.info("invalid request detail={}", ex.getMessage());
        return ErrorCode.INVALID_REQUEST.toResponseEntity(cartIdOf(exchange));
    }

    /** 405, 406 and 415 keep their status and get the same error object as everything else. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleRejectedRequest(ResponseStatusException ex,
                                                               ServerWebExchange exchange) {
        if (!ex.getStatusCode().is4xxClientError()) {
            return handleUnexpected(ex, exchange);
        }
        log.info("request rejected status={} reason={}", ex.getStatusCode().value(), ex.getReason());
        return ResponseEntity.status(ex.getStatusCode())
                .body(ErrorCode.INVALID_REQUEST.toResponse(cartIdOf(exchange)));
    }

    @ExceptionHandler({ServiceUnavailableException.class, DataAccessResourceFailureException.class,
            TransientDataAccessException.class})
    public ResponseEntity<ErrorResponse> handleUnavailable(Exception ex, ServerWebExchange exchange) {
        log.warn("dependency unavailable cartId={} cause={}", cartIdOf(exchange), ex.getMessage());
        return ErrorCode.SERVICE_UNAVAILABLE.toResponseEntity(cartIdOf(exchange));
    }

    @ExceptionHandler(Throwable.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Throwable ex, ServerWebExchange exchange) {
        log.error("unexpected failure cartId={}", cartIdOf(exchange), ex);
        return ErrorCode.INTERNAL_ERROR.toResponseEntity(cartIdOf(exchange));
    }

    private static UUID cartIdOf(ServerWebExchange exchange) {
        return exchange.getAttribute(CART_ID_ATTRIBUTE);
    }
}
