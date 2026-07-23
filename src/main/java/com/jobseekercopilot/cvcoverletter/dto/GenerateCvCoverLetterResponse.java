package com.jobseekercopilot.cvcoverletter.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GenerateCvCoverLetterResponse {
    private String applicationId;
    private String cvDocumentId;
    private String coverLetterDocumentId;
    private String cvTitle;
    private String coverLetterTitle;
    private String cvContent;
    private String coverLetterContent;
    private GenerationNotes generationNotes;
}
