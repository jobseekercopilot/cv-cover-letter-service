package com.jobseekercopilot.cvcoverletter.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GenerateRequest extends StrictInput {

    @NotBlank
    @Size(max = 8)
    @Pattern(regexp = "1\\.0", message = "must be 1.0")
    private String inputSchemaVersion;

    @NotNull
    @Valid
    private ProfileInputSnapshot profile;

    @NotNull
    @Valid
    private JobInputSnapshot job;
}
