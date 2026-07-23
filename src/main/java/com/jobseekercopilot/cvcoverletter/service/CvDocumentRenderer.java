package com.jobseekercopilot.cvcoverletter.service;

import com.jobseekercopilot.cvcoverletter.dto.GeneratedCv;
import com.jobseekercopilot.cvcoverletter.dto.ContactDetails;
import com.jobseekercopilot.cvcoverletter.dto.GeneratedQualification;
import com.jobseekercopilot.cvcoverletter.dto.GeneratedWorkHistory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class CvDocumentRenderer {

    public String render(GeneratedCv cv) {
        return render(cv, new ContactDetails(null, null, null));
    }

    public String render(GeneratedCv cv, ContactDetails contactDetails) {
        List<String> sections = new ArrayList<>();
        sections.add(cv.getTitle());
        if (contactDetails != null && contactDetails.hasAny()) {
            sections.add(String.join("\n", contactDetails.lines()));
        }
        sections.add("Personal Summary\n" + cv.getPersonalSummary());

        StringBuilder skills = new StringBuilder("Core Skills");
        cv.getCoreSkills().forEach(skill -> skills.append("\n- ").append(skill.getName())
                .append(hasText(skill.getEvidence()) ? ": " + skill.getEvidence() : ""));
        sections.add(skills.toString());

        StringBuilder history = new StringBuilder("Work History");
        for (GeneratedWorkHistory role : cv.getWorkHistory()) {
            history.append("\n").append(join(" - ", role.getJobTitle(), role.getEmployer()));
            String dates = join(" - ", role.getStartDate(), role.getEndDate());
            if (hasText(dates)) {
                history.append("\n").append(dates);
            }
            if (role.getResponsibilities() != null) {
                role.getResponsibilities().stream().filter(this::hasText)
                        .forEach(item -> history.append("\n- ").append(item));
            }
            if (hasText(role.getTailoredDescription())) {
                history.append("\n").append(role.getTailoredDescription());
            }
        }
        sections.add(history.toString());

        StringBuilder qualifications = new StringBuilder("Qualifications");
        for (GeneratedQualification qualification : cv.getQualifications()) {
            String date = hasText(qualification.getDateAchieved())
                    ? qualification.getDateAchieved() : qualification.getExpectedCompletion();
            qualifications.append("\n- ").append(join(", ", qualification.getQualificationName(),
                    qualification.getIssuingBody(), qualification.getStatus(), qualification.getGrade(), date));
        }
        sections.add(qualifications.toString());
        return String.join("\n\n", sections).trim();
    }

    private String join(String separator, String... values) {
        return java.util.Arrays.stream(values).filter(this::hasText)
                .collect(java.util.stream.Collectors.joining(separator));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
