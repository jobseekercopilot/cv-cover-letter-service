package com.jobseekercopilot.cvcoverletter.model;

public enum EvidencePurpose {
    CV,
    COVER_LETTER,
    BOTH;

    public boolean supports(EvidencePurpose required) {
        return this == BOTH || this == required;
    }
}
