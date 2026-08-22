package com.jobseekercopilot.cvcoverletter.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class EvidenceSnapshotInput extends StrictInput {

    @NotNull
    private UUID snapshotId;

    @NotNull
    private EvidenceSnapshotPurpose purpose;

    @NotNull
    private UUID profileRevisionId;

    @NotNull
    @Pattern(regexp = "[a-f0-9]{64}")
    private String profileContentDigest;

    @NotEmpty
    @Size(max = 9)
    private List<@NotNull EvidenceCategory> sectionOrder =
            new ArrayList<>();

    @Valid
    @NotEmpty
    @Size(max = 50)
    private List<EvidenceSnapshotSelectionInput> selections =
            new ArrayList<>();

    @NotNull
    @Pattern(regexp = "[a-f0-9]{64}")
    private String snapshotDigest;

    @NotNull
    private Instant createdAt;
}
