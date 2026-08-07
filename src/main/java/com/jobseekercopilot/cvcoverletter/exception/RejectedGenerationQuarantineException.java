package com.jobseekercopilot.cvcoverletter.exception;

public class RejectedGenerationQuarantineException extends RuntimeException {
    public RejectedGenerationQuarantineException(String message) {
        super(message);
    }

    public RejectedGenerationQuarantineException(String message, Throwable cause) {
        super(message, cause);
    }
}
