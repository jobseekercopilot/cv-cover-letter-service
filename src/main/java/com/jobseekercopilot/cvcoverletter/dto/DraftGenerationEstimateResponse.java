package com.jobseekercopilot.cvcoverletter.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Conservative token reservation required before one draft generation")
public record DraftGenerationEstimateResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1")
        long estimatedTokens) {
}
