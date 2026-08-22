package com.jobseekercopilot.cvcoverletter.service;

import com.jobseekercopilot.cvcoverletter.dto.ContactDetails;
import com.jobseekercopilot.cvcoverletter.dto.GeneratedCoverLetter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class CoverLetterDocumentRenderer {

    public String render(GeneratedCoverLetter letter) {
        return render(letter, new ContactDetails(null, null, null));
    }

    public String render(GeneratedCoverLetter letter, ContactDetails contactDetails) {
        List<String> parts = new ArrayList<>();
        parts.add(letter.getTitle());
        if (contactDetails != null && contactDetails.hasAny()) {
            parts.add(String.join("\n", contactDetails.lines()));
        }
        if (hasText(letter.getJobTitle())
                && hasText(letter.getCompanyName())) {
            parts.add("Application for "
                    + letter.getJobTitle().trim()
                    + " at "
                    + letter.getCompanyName().trim());
        }
        parts.add(punctuated(letter.getGreeting(), "Dear Hiring Manager", ","));
        parts.add(letter.getOpeningParagraph());
        parts.addAll(letter.getBodyParagraphs());
        parts.add(letter.getClosingParagraph());
        parts.add(signOff(letter, contactDetails));
        return parts.stream().filter(value -> value != null && !value.isBlank())
                .collect(java.util.stream.Collectors.joining("\n\n"));
    }

    private String signOff(
            GeneratedCoverLetter letter,
            ContactDetails contactDetails) {
        String fullName = contactDetails == null ? null : contactDetails.fullName();
        String signOff = punctuated(
                letter.getSignOff(),
                "Yours faithfully",
                ",");
        return fullName == null || fullName.isBlank()
                ? signOff
                : signOff + "\n" + fullName.trim();
    }

    private String punctuated(
            String value,
            String fallback,
            String punctuation) {
        String text = hasText(value) ? value.trim() : fallback;
        return text.endsWith(punctuation) ? text : text + punctuation;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
