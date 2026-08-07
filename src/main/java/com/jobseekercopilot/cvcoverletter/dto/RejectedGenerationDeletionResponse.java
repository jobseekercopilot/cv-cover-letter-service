package com.jobseekercopilot.cvcoverletter.dto;

import java.time.Instant;
import java.util.UUID;

public record RejectedGenerationDeletionResponse(
        UUID operationId,
        Instant deletedAt) {
}
