package com.jobseekercopilot.cvcoverletter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class ProfessionalLinkInput extends StrictInput {

    @NotBlank
    @Size(max = 40)
    @Pattern(regexp = "[^\\p{Cc}]+")
    private String label;

    @NotBlank
    @Size(min = 9, max = 512)
    @Pattern(regexp = "[^\\p{Cc}]+")
    private String url;

    @Override
    public String toString() {
        return "ProfessionalLinkInput(redacted)";
    }
}
