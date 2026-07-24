package com.jobseekercopilot.cvcoverletter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class QualificationInput extends StrictInput {

    @NotBlank
    @Size(max = 160)
    private String qualificationName;

    @Size(max = 160)
    private String issuingBody;

    private QualificationStatus status;

    @Size(max = 80)
    private String grade;

    @Size(max = 10)
    private String dateAchieved;

    @Size(max = 10)
    private String expectedCompletion;
}
