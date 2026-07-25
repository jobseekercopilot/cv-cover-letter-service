package com.jobseekercopilot.cvcoverletter.dto;

import java.util.List;
import lombok.Data;

@Data
public class GeneratedClaim {
    private String claimId;
    private ClaimDisposition disposition;
    private List<String> evidenceIds;
    private List<String> contentPaths;
    private String reviewText;
}
