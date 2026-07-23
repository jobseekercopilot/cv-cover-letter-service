package com.jobseekercopilot.cvcoverletter.dto;

import lombok.Data;

import java.util.List;

@Data
public class GeneratedWorkHistory {
    private String jobTitle;
    private String employer;
    private String startDate;
    private String endDate;
    private List<String> responsibilities;
    private String tailoredDescription;
}
