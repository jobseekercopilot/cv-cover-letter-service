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
        parts.add("Dear Hiring Manager,");
        parts.add(letter.getOpeningParagraph());
        parts.addAll(letter.getBodyParagraphs());
        parts.add(letter.getClosingParagraph());
        parts.add(signOff(contactDetails));
        return parts.stream().filter(value -> value != null && !value.isBlank())
                .collect(java.util.stream.Collectors.joining("\n\n"));
    }

    private String signOff(ContactDetails contactDetails) {
        String fullName = contactDetails == null ? null : contactDetails.fullName();
        return fullName == null || fullName.isBlank()
                ? "Yours faithfully,"
                : "Yours faithfully,\n" + fullName.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
