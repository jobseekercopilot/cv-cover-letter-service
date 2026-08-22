package com.jobseekercopilot.cvcoverletter.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class ContactInputSnapshot extends StrictInput {

    @NotNull
    @Valid
    private SnapshotProvenance provenance;

    @Size(max = 120)
    private String fullName;

    @Email
    @Size(max = 254)
    private String email;
}
