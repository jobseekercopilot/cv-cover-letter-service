package com.jobseekercopilot.cvcoverletter.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class JobInputSnapshot extends StrictInput {

    @NotNull
    @Valid
    private SnapshotProvenance provenance;

    @NotBlank
    @Size(max = 160)
    private String title;

    @NotBlank
    @Size(max = 160)
    private String company;

    @Size(max = 160)
    private String location;

    @Size(max = 80)
    private String employmentType;

    private LocalDate postedDate;

    @NotBlank
    @Size(max = 12000)
    private String description;
}
