package com.jobseekercopilot.cvcoverletter.dto;

import java.util.List;
import lombok.Data;

@Data
public class GeneratedApplicationDocuments {
    private GeneratedCv cv;
    private GeneratedCoverLetter coverLetter;
    private GenerationNotes generationNotes;
    private List<GeneratedClaim> claims;
}
