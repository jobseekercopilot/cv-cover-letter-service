package com.jobseekercopilot.cvcoverletter.exception;

public class RejectedGenerationNotFoundException extends RuntimeException {
    public RejectedGenerationNotFoundException() {
        super("Rejected generation artifact was not found.");
    }
}
