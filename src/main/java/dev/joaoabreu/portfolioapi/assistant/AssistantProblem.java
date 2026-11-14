package dev.joaoabreu.portfolioapi.assistant;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

public record AssistantProblem(String type, String title, int status, String code, String detail,
        String instance, @JsonInclude(JsonInclude.Include.NON_EMPTY) List<FieldError> fieldErrors) {
    static AssistantProblem of(AssistantException.Code code, List<FieldError> fields) {
        String title = switch (code) {
            case INVALID_REQUEST -> "Invalid request";
            case REQUEST_TOO_LARGE -> "Request too large";
            case UNSUPPORTED_MEDIA_TYPE -> "Unsupported media type";
            case RATE_LIMITED -> "Rate limit exceeded";
            case ASSISTANT_UNAVAILABLE -> "Assistant unavailable";
            case ASSISTANT_TIMEOUT -> "Assistant timeout";
            case INVALID_ASSISTANT_RESPONSE -> "Invalid assistant response";
            case INTERNAL_ERROR -> "Internal error";
        };
        String detail = switch (code) {
            case INVALID_REQUEST -> "Check the request fields and JSON format.";
            case REQUEST_TOO_LARGE -> "The request body must not exceed 16 KiB.";
            case UNSUPPORTED_MEDIA_TYPE -> "Send uncompressed application/json encoded in UTF-8.";
            case RATE_LIMITED -> "Wait before trying again.";
            case ASSISTANT_UNAVAILABLE -> "The assistant is temporarily unavailable.";
            case ASSISTANT_TIMEOUT -> "The assistant did not respond in time.";
            case INVALID_ASSISTANT_RESPONSE -> "A valid assistant response could not be obtained.";
            case INTERNAL_ERROR -> "An unexpected error occurred.";
        };
        return new AssistantProblem("about:blank", title, code.httpStatus(), code.name(), detail,
                AssistantController.PATH, List.copyOf(fields));
    }

    public record FieldError(String field, String code) {}
}
