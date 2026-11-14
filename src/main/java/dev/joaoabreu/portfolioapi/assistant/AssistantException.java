package dev.joaoabreu.portfolioapi.assistant;

public class AssistantException extends RuntimeException {
    public enum Code {
        INVALID_REQUEST(400), REQUEST_TOO_LARGE(413), UNSUPPORTED_MEDIA_TYPE(415), RATE_LIMITED(429),
        ASSISTANT_UNAVAILABLE(503), ASSISTANT_TIMEOUT(504),
        INVALID_ASSISTANT_RESPONSE(502), INTERNAL_ERROR(500);

        private final int httpStatus;
        Code(int httpStatus) { this.httpStatus = httpStatus; }
        public int httpStatus() { return httpStatus; }
    }

    private final Code code;
    private final java.util.List<AssistantProblem.FieldError> fieldErrors;

    public AssistantException(Code code) {
        this(code, java.util.List.of());
    }

    public AssistantException(Code code, java.util.List<AssistantProblem.FieldError> fieldErrors) {
        // Do not retain provider messages, raw responses, prompts or credentials in exceptions.
        super(code.name());
        this.code = code;
        this.fieldErrors = java.util.List.copyOf(fieldErrors);
    }

    public Code code() { return code; }
    public java.util.List<AssistantProblem.FieldError> fieldErrors() { return fieldErrors; }
}
