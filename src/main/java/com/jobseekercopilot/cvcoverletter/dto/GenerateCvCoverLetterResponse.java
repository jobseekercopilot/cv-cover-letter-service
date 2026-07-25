package com.jobseekercopilot.cvcoverletter.dto;

import java.util.List;
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
    private PromptGenerationMetadata generationMetadata;
    private String inputSchemaVersion;
    private List<InputWarning> inputWarnings;
}
