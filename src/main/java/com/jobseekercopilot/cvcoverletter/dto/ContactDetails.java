package com.jobseekercopilot.cvcoverletter.dto;

import java.util.List;
import java.util.stream.Stream;

public record ContactDetails(String fullName, String email, String location) {

    public List<String> lines() {
        return Stream.of(fullName, email, location)
                .filter(ContactDetails::hasText)
                .toList();
    }

    public boolean hasAny() {
        return hasText(fullName) || hasText(email) || hasText(location);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
