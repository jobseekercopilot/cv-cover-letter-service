package com.jobseekercopilot.cvcoverletter.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class SelectedDraftGenerationRequest extends StrictInput {

    @NotBlank
    @Size(max = 8)
    @Pattern(regexp = "2\\.0", message = "must be 2.0")
    private String inputSchemaVersion;

    @NotNull
    @Valid
    private ProfileInputSnapshot profile;

    @NotNull
    @Valid
    private JobInputSnapshot job;

    @NotNull
    @Valid
    private EvidenceSnapshotInput evidenceSnapshot;
}
