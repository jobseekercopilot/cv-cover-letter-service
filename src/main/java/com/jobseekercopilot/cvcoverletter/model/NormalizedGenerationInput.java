package com.jobseekercopilot.cvcoverletter.model;

import com.jobseekercopilot.cvcoverletter.dto.ContactDetails;
import com.jobseekercopilot.cvcoverletter.dto.InputWarning;
import com.jobseekercopilot.cvcoverletter.dto.QualificationStatus;
import com.jobseekercopilot.cvcoverletter.dto.RoleStatus;
import com.jobseekercopilot.cvcoverletter.dto.SnapshotProvenance;
import java.time.LocalDate;
import java.util.List;

public record NormalizedGenerationInput(
        String ownerId,
        String inputSchemaVersion,
        SnapshotProvenance profileProvenance,
        SnapshotProvenance contactProvenance,
        SnapshotProvenance jobProvenance,
        ContactDetails contact,
        PromptProfile profile,
        PromptJob job,
        List<InputWarning> warnings) {

    public record PromptProfile(
            List<String> skills,
            List<String> targetRoles,
            List<PromptQualification> qualifications,
            List<PromptEmployment> employmentHistory) {
    }

    public record PromptQualification(
            String qualificationName,
            String issuingBody,
            QualificationStatus status,
            String grade,
            String dateAchieved,
            String expectedCompletion) {
    }

    public record PromptEmployment(
            String jobTitle,
            String employer,
            RoleStatus status,
            String startDate,
            String endDate,
            String responsibilities) {
    }

    public record PromptJob(
            String title,
            String company,
            String location,
            String employmentType,
            LocalDate postedDate,
            String description) {
    }
}
