package com.jobseekercopilot.cvcoverletter.dto;

import java.util.List;
import lombok.Data;

@Data
public class GeneratedProject {
    private String title;
    private String role;
    private String context;
    private String startDate;
    private String endDate;
    private String description;
    private List<String> highlights;
}
