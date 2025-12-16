package com.plantloom.api;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.plantloom.definition.DefinitionValidationException;
import com.plantloom.definition.UnknownDefinitionException;
import com.plantloom.engine.CapacityExceededException;
import com.plantloom.engine.UnknownInstanceException;
import com.plantloom.equipment.UnknownEquipmentException;
import com.plantloom.rules.RuleEvaluationException;

/** Maps domain exceptions to RFC 7807 problem responses. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler({UnknownDefinitionException.class, UnknownInstanceException.class,
            UnknownEquipmentException.class})
    ProblemDetail notFound(RuntimeException e) {
        return problem(HttpStatus.NOT_FOUND, "Not found", e.getMessage());
    }

    @ExceptionHandler(CapacityExceededException.class)
    ProblemDetail capacity(CapacityExceededException e) {
        return problem(HttpStatus.TOO_MANY_REQUESTS, "At capacity", e.getMessage());
    }

    @ExceptionHandler(DefinitionValidationException.class)
    ProblemDetail invalidDefinition(DefinitionValidationException e) {
        ProblemDetail detail = problem(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid workflow definition", e.getMessage());
        detail.setProperty("problems", e.getProblems());
        return detail;
    }

    @ExceptionHandler({RuleEvaluationException.class, IllegalArgumentException.class, IOException.class})
    ProblemDetail badRequest(Exception e) {
        return problem(HttpStatus.BAD_REQUEST, "Bad request", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation(MethodArgumentNotValidException e) {
        ProblemDetail detail = problem(HttpStatus.BAD_REQUEST, "Validation failed", "Request body is invalid");
        detail.setProperty("errors", e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .toList());
        return detail;
    }

    private static ProblemDetail problem(HttpStatus status, String title, String message) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, message);
        detail.setTitle(title);
        return detail;
    }
}
