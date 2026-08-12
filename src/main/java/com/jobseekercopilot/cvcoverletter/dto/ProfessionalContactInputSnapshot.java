package com.jobseekercopilot.cvcoverletter.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class ProfessionalContactInputSnapshot extends StrictInput {

    @Size(max = 40)
    @Pattern(regexp = "[0-9+() .-]*")
    private String phone;

    @Valid
    @Size(max = 8)
    private List<ProfessionalLinkInput> links = new ArrayList<>();

    @Override
    public String toString() {
        return "ProfessionalContactInputSnapshot(redacted)";
    }
}
