package com.jobseekercopilot.cvcoverletter.dto;

import lombok.Data;

import java.util.List;

@Data
public class GenerationNotes {
    private List<String> assumptionsMade;
    private List<String> missingInformation;
    private String tailoringSummary;
}
