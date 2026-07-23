package com.jobseekercopilot.cvcoverletter.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Qualification {

    private Long id;
    private String qualificationName;
    private String issuingBody;
    private QualificationStatus status;
    private String grade;
    private String dateAchieved;
    private String expectedCompletion;
}
