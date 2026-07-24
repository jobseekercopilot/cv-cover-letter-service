package com.jobseekercopilot.cvcoverletter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmploymentInput extends StrictInput {

    @NotBlank
    @Size(max = 160)
    private String jobTitle;

    @NotBlank
    @Size(max = 160)
    private String employer;

    private RoleStatus status;

    @NotBlank
    @Size(max = 10)
    private String startDate;

    @Size(max = 10)
    private String endDate;

    @Size(max = 4000)
    private String responsibilities;
}
