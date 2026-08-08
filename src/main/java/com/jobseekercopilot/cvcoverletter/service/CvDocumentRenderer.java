package com.jobseekercopilot.cvcoverletter.service;

import com.jobseekercopilot.cvcoverletter.dto.ContactDetails;
import com.jobseekercopilot.cvcoverletter.dto.GeneratedCv;
import com.jobseekercopilot.cvcoverletter.dto.GeneratedProject;
import com.jobseekercopilot.cvcoverletter.dto.GeneratedQualification;
import com.jobseekercopilot.cvcoverletter.dto.GeneratedWorkHistory;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

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

        if (hasText(cv.getPersonalSummary())) {
            String profileHeading = safe(cv.getProjects()).isEmpty()
                    || !safe(cv.getWorkHistory()).isEmpty()
                    ? "Professional Profile"
                    : "Technical Profile";
            sections.add(profileHeading + "\n" + cv.getPersonalSummary());
        }

        LinkedHashSet<String> skillNames = new LinkedHashSet<>();
        safe(cv.getCoreSkills()).stream()
                .map(GeneratedCv.CoreSkill::getName)
                .filter(this::hasText)
                .map(String::trim)
                .limit(12)
                .forEach(skillNames::add);
        if (!skillNames.isEmpty()) {
            sections.add("Technical Skills\n" + String.join(", ", skillNames));
        }

        if (!safe(cv.getWorkHistory()).isEmpty()) {
            StringBuilder history = new StringBuilder("Professional Experience");
            for (GeneratedWorkHistory role : cv.getWorkHistory()) {
                history.append("\n").append(join(
                        " - ",
                        role.getJobTitle(),
                        role.getEmployer()));
                String dates = dateRange(role.getStartDate(), role.getEndDate());
                if (hasText(dates)) {
                    history.append("\n").append(dates);
                }
                safe(role.getResponsibilities()).stream()
                        .filter(this::hasText)
                        .forEach(item -> history.append("\n- ").append(item));
                if (hasText(role.getTailoredDescription())) {
                    history.append("\n").append(role.getTailoredDescription());
                }
            }
            sections.add(history.toString());
        }

        if (!safe(cv.getProjects()).isEmpty()) {
            StringBuilder projects = new StringBuilder("Selected Projects");
            for (GeneratedProject project : cv.getProjects()) {
                String heading = join(" - ", project.getTitle(), project.getRole());
                if (hasText(heading)) {
                    projects.append("\n").append(heading);
                }
                if (hasText(project.getContext())) {
                    projects.append("\n").append(project.getContext());
                }
                String dates = dateRange(project.getStartDate(), project.getEndDate());
                if (hasText(dates)) {
                    projects.append("\n").append(dates);
                }
                if (hasText(project.getDescription())) {
                    projects.append("\n").append(project.getDescription());
                }
                safe(project.getHighlights()).stream()
                        .filter(this::hasText)
                        .forEach(item -> projects.append("\n- ").append(item));
            }
            sections.add(projects.toString());
        }

        if (!safe(cv.getQualifications()).isEmpty()) {
            StringBuilder qualifications =
                    new StringBuilder("Education and Qualifications");
            for (GeneratedQualification qualification : cv.getQualifications()) {
                String date = hasText(qualification.getDateAchieved())
                        ? qualification.getDateAchieved()
                        : qualification.getExpectedCompletion();
                qualifications.append("\n- ").append(joinDistinct(
                        ", ",
                        qualification.getQualificationName(),
                        qualification.getIssuingBody(),
                        qualification.getStatus(),
                        qualification.getGrade(),
                        naturalDate(date)));
            }
            sections.add(qualifications.toString());
        }
        return sections.stream()
                .filter(this::hasText)
                .collect(java.util.stream.Collectors.joining("\n\n"))
                .trim();
    }

    private <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private String join(String separator, String... values) {
        return java.util.Arrays.stream(values)
                .filter(this::hasText)
                .map(String::trim)
                .collect(java.util.stream.Collectors.joining(separator));
    }

    private String joinDistinct(String separator, String... values) {
        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        java.util.Arrays.stream(values)
                .filter(this::hasText)
                .map(String::trim)
                .forEach(value -> {
                    if (distinct.stream().noneMatch(existing ->
                            existing.equalsIgnoreCase(value))) {
                        distinct.add(value);
                    }
                });
        return String.join(separator, distinct);
    }

    private String dateRange(String start, String end) {
        return join(" – ", naturalDate(start), naturalDate(end));
    }

    private String naturalDate(String value) {
        if (!hasText(value)) {
            return value;
        }
        String trimmed = value.trim();
        if (trimmed.equalsIgnoreCase("present")
                || trimmed.equalsIgnoreCase("current")) {
            return "Present";
        }
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(
                "MMMM uuuu",
                Locale.UK);
        try {
            return YearMonth.parse(trimmed).format(formatter);
        } catch (DateTimeParseException ignored) {
            try {
                return YearMonth.from(LocalDate.parse(trimmed))
                        .format(formatter);
            } catch (DateTimeParseException alsoIgnored) {
                return trimmed;
            }
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
