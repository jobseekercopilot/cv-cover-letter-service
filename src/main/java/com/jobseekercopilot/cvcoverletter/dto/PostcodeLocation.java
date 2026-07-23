package com.jobseekercopilot.cvcoverletter.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PostcodeLocation {

    private String postcode;

    private String region;

    private String adminDistrict;

    private Double latitude;

    private Double longitude;
}
