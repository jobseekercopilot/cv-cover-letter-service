package com.jobseekercopilot.cvcoverletter.dto;

import lombok.Data;

@Data
public class GeneratedApplicationDocuments {
    private GeneratedCv cv;
    private GeneratedCoverLetter coverLetter;
    private GenerationNotes generationNotes;
}
