package dev.joaoabreu.portfolioapi.assistant;

import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AssistantController.class)
public class AssistantErrorHandler {
    @ExceptionHandler(AssistantException.class)
    ResponseEntity<AssistantProblem> assistant(AssistantException exception) {
        return response(exception.code(), exception.fieldErrors());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<AssistantProblem> unreadable() {
        return response(AssistantException.Code.INVALID_REQUEST, List.of());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<AssistantProblem> unsupported() {
        return response(AssistantException.Code.UNSUPPORTED_MEDIA_TYPE, List.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<AssistantProblem> unexpected() {
        return response(AssistantException.Code.INTERNAL_ERROR, List.of());
    }

    private static ResponseEntity<AssistantProblem> response(AssistantException.Code code,
            List<AssistantProblem.FieldError> fields) {
        return ResponseEntity.status(code.httpStatus()).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .cacheControl(CacheControl.noStore()).body(AssistantProblem.of(code, fields));
    }
}
