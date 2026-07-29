package com.jobseekercopilot.cvcoverletter.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class EvidenceSnapshotsInput extends StrictInput {

    @Valid
    @NotNull
    private EvidenceSnapshotInput cv;

    @Valid
    @NotNull
    private EvidenceSnapshotInput coverLetter;
}
