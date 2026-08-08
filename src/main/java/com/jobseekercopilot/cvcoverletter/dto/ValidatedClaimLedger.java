package com.jobseekercopilot.cvcoverletter.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

@Schema(
        description = """
                Exact bounded claim ledger accepted by strict evidence validation.
                It contains stable evidence identifiers and content paths, not raw
                claimant evidence values.
                """)
public record ValidatedClaimLedger(
        @NotNull
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        UUID ledgerId,
        @NotBlank
        @Pattern(regexp = "^[a-f0-9]{64}$")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String ledgerSha256,
        @NotBlank
        @Size(max = 32)
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String policyVersion,
        @NotBlank
        @Size(max = 32)
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String parserVersion,
        @Valid
        @NotEmpty
        @Size(max = 200)
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        List<ValidatedClaim> claims) {

    public ValidatedClaimLedger {
        claims = claims == null ? null : List.copyOf(claims);
    }

    @Schema(description = "One validated claim and its exact supporting identifiers")
    public record ValidatedClaim(
            @NotBlank
            @Pattern(regexp = "^CLAIM-[0-9]{3,4}$")
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String claimId,
            @NotNull
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            ClaimDisposition disposition,
            @NotNull
            @Size(max = 30)
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            List<
                    @NotBlank
                    @Pattern(regexp = "^[A-Za-z0-9._-]{3,160}$")
                    String> evidenceIds,
            @NotNull
            @Size(max = 30)
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            List<
                    @NotBlank
                    @Pattern(regexp = "^/(cv|coverLetter)(/[A-Za-z0-9_-]+)+$")
                    String> contentPaths,
            @NotNull
            @Size(max = 500)
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String reviewText) {

        public ValidatedClaim {
            evidenceIds =
                    evidenceIds == null ? null : List.copyOf(evidenceIds);
            contentPaths =
                    contentPaths == null ? null : List.copyOf(contentPaths);
        }
    }
}
