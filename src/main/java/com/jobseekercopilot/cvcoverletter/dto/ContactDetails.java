package com.jobseekercopilot.cvcoverletter.dto;

import java.util.List;
import java.util.stream.Stream;

public record ContactDetails(String fullName, String email, String location) {

    public static ContactDetails from(UserProfile profile) {
        if (profile == null) {
            return new ContactDetails(null, null, null);
        }
        return new ContactDetails(
                blankToNull(profile.getFullName()),
                blankToNull(profile.getEmail()),
                location(profile.getWorkPreferences()));
    }

    public List<String> lines() {
        return Stream.of(fullName, email, location)
                .filter(ContactDetails::hasText)
                .toList();
    }

    public boolean hasAny() {
        return hasText(fullName) || hasText(email) || hasText(location);
    }

    private static String location(WorkPreferences workPreferences) {
        if (workPreferences == null || workPreferences.getLocation() == null) {
            return null;
        }
        PostcodeLocation location = workPreferences.getLocation();
        String joined = Stream.of(location.getAdminDistrict(), location.getRegion(), location.getPostcode())
                .filter(ContactDetails::hasText)
                .distinct()
                .reduce((left, right) -> left + ", " + right)
                .orElse(null);
        return blankToNull(joined);
    }

    private static String blankToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
