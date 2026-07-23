package com.jobseekercopilot.cvcoverletter.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class JobSalary {
    private Integer min;
    private Integer max;
    private String currency;
}
