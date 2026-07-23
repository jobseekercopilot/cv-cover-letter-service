package com.jobseekercopilot.cvcoverletter.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.validation.constraints.NotBlank;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserProfile {

    private Long id;

    @NotBlank
    private String userId;

    private String fullName;

    private String email;

    private List<String> skills = new ArrayList<>();

    private Aspirations aspirations;

    private WorkPreferences workPreferences;

    private List<Qualification> qualifications = new ArrayList<>();

    private List<Role> roles = new ArrayList<>();
}
