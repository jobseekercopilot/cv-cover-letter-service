package com.jobseekercopilot.cvcoverletter.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
public class EvidenceSnapshotSelectionInput extends StrictInput {

    @NotNull
    private UUID entryId;

    @NotNull
    private UUID revisionId;

    @Min(1)
    @NotNull
    private Integer revisionNumber;

    @NotNull
    private EvidenceCategory category;

    @NotNull
    @Pattern(regexp = "[a-f0-9]{64}")
    private String contentDigest;

    @Valid
    @NotEmpty
    @Size(max = 50)
    private List<EvidenceSnapshotFactInput> facts = new ArrayList<>();
}
