package com.jobseekercopilot.cvcoverletter.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Role {

    private Long id;
    private String jobTitle;
    private String employer;
    private RoleStatus status;
    private String startDate;
    private String endDate;
    private String keyResponsibilities;
}
