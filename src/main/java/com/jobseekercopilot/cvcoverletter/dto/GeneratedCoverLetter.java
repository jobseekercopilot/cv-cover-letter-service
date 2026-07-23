package com.jobseekercopilot.cvcoverletter.dto;

import lombok.Data;

import java.util.List;

@Data
public class GeneratedCoverLetter {
    private String title;
    private String jobTitle;
    private String companyName;
    private String greeting;
    private String openingParagraph;
    private List<String> bodyParagraphs;
    private String closingParagraph;
    private String signOff;
}
