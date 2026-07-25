package com.jobseekercopilot.cvcoverletter.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class ProfileInputSnapshot extends StrictInput {

    @NotNull
    @Valid
    private SnapshotProvenance provenance;

    @Valid
    private ContactInputSnapshot contact;

    @Size(max = 160)
    private String location;

    @Size(max = 40)
    private List<@Size(max = 100) String> skills = new ArrayList<>();

    @Size(max = 20)
    private List<@Size(max = 120) String> targetRoles = new ArrayList<>();

    @Valid
    @Size(max = 30)
    private List<QualificationInput> qualifications = new ArrayList<>();

    @Valid
    @Size(max = 30)
    private List<EmploymentInput> employmentHistory = new ArrayList<>();
}
