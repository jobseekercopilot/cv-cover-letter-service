package com.jobseekercopilot.cvcoverletter.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;

public abstract class StrictInput {

    @JsonAnySetter
    @Schema(hidden = true)
    public final void rejectUnknownField(String name, Object ignoredValue) {
        throw new IllegalArgumentException("Unknown generation input field: " + name);
    }
}
