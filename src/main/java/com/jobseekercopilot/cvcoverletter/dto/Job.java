package com.jobseekercopilot.cvcoverletter.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.validation.constraints.NotBlank;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Job {
    @NotBlank
    private String id;
    @NotBlank
    private String title;
    @NotBlank
    private String company;
    private String location;
    private JobSalary salary;
    private String employmentType;
    private String postedDate;
    @NotBlank
    private String description;
    private String url;
    private Double matchScore;
}
