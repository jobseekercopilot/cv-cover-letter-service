package com.jobseekercopilot.cvcoverletter.dto;

import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Aspirations {

    private List<String> targetRoles = new ArrayList<>();
    private TargetWeeklyHours targetWeeklyHours;
}
