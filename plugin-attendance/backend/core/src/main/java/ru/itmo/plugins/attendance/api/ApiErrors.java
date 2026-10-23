package ru.itmo.plugins.attendance.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import ru.itmo.plugins.attendance.tracking.TooManyCallsException;

/** Ошибки API в формате problem+json: UI показывает поле {@code detail}. */
@RestControllerAdvice
class ApiErrors {

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
