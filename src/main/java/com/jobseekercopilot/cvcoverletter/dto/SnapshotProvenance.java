package com.jobseekercopilot.cvcoverletter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SnapshotProvenance extends StrictInput {

    @NotNull
    private InputSourceOwner owner;

    @NotBlank
    @Size(max = 128)
    private String resourceId;

    @NotBlank
    @Size(max = 128)
    private String version;

    @NotNull
    private Instant capturedAt;
}
