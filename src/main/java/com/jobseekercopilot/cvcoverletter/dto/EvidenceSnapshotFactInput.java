package com.jobseekercopilot.cvcoverletter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class EvidenceSnapshotFactInput extends StrictInput {

    @NotNull
    private UUID factId;

    @NotBlank
    @Size(max = 80)
    @Pattern(regexp = "[A-Z][A-Z0-9_]{0,79}")
    private String factType;

    @NotBlank
    @Size(max = 4000)
    private String factValue;

    @NotNull
    private Boolean numericClaim;
}
