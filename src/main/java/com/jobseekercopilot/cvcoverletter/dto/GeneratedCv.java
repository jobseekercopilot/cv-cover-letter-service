package com.jobseekercopilot.cvcoverletter.dto;

import lombok.Data;

import java.util.List;

@Data
public class GeneratedCv {
    private String title;
    private String targetRole;
    private String personalSummary;
    private List<CoreSkill> coreSkills;
    private List<GeneratedQualification> qualifications;
    private List<GeneratedWorkHistory> workHistory;

    @Data
    public static class CoreSkill {
        private String name;
        private String evidence;
    }
}
