package com.jobseekercopilot.cvcoverletter.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public record ContactDetails(
        String fullName,
        String email,
        String location,
        String phone,
        List<ProfessionalLink> links) {

    public record ProfessionalLink(String label, String url) {
    }

    public ContactDetails(String fullName, String email, String location) {
        this(fullName, email, location, null, List.of());
    }

    public ContactDetails {
        links = links == null ? List.of() : List.copyOf(links);
    }

    public List<String> lines() {
        List<String> lines = new ArrayList<>(Stream.of(
                        fullName, email, location, phone)
                .filter(ContactDetails::hasText).toList());
        links.stream()
                .map(link -> link.label() + ": " + link.url())
                .forEach(lines::add);
        return List.copyOf(lines);
    }

    public boolean hasAny() {
        return hasText(fullName)
                || hasText(email)
                || hasText(location)
                || hasText(phone)
                || !links.isEmpty();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
