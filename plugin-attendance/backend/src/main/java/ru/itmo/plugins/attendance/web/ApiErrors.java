package ru.itmo.plugins.attendance.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import ru.itmo.plugins.attendance.service.activation.ActivationException;
import ru.itmo.plugins.attendance.service.tracking.TooManyCallsException;

/** Ошибки API в формате problem+json: UI показывает поле {@code detail}. */
@RestControllerAdvice
class ApiErrors {

    @ExceptionHandler(ActivationException.class)
    ProblemDetail activationError(ActivationException exception) {
        HttpStatus status = switch (exception.reason()) {
            case CONFLICT -> HttpStatus.CONFLICT;
            case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case FAILED -> HttpStatus.BAD_GATEWAY;
            case STOPPED -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        var problem = ProblemDetail.forStatusAndDetail(status, exception.getMessage());
        problem.setProperty("code", "ACTIVATION_" + exception.reason());
        return problem;
    }

    @ExceptionHandler(ApiException.class)
    ProblemDetail apiError(ApiException exception) {
        return ProblemDetail.forStatusAndDetail(exception.status(), exception.getMessage());
    }

    @ExceptionHandler(TooManyCallsException.class)
    ProblemDetail tooManyCalls(TooManyCallsException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Плагин уже отслеживает " + exception.limit() + " звонков, попробуйте позже");
    }
}
