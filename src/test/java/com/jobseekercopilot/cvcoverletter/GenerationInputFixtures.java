package com.jobseekercopilot.cvcoverletter;

import com.jobseekercopilot.cvcoverletter.dto.ContactInputSnapshot;
import com.jobseekercopilot.cvcoverletter.dto.EmploymentInput;
import com.jobseekercopilot.cvcoverletter.dto.EvidenceCategory;
import com.jobseekercopilot.cvcoverletter.dto.EvidenceSnapshotFactInput;
import com.jobseekercopilot.cvcoverletter.dto.EvidenceSnapshotInput;
import com.jobseekercopilot.cvcoverletter.dto.EvidenceSnapshotPurpose;
import com.jobseekercopilot.cvcoverletter.dto.EvidenceSnapshotSelectionInput;
import com.jobseekercopilot.cvcoverletter.dto.EvidenceSnapshotsInput;
import com.jobseekercopilot.cvcoverletter.dto.GenerateRequest;
import com.jobseekercopilot.cvcoverletter.dto.InputSourceOwner;
import com.jobseekercopilot.cvcoverletter.dto.JobInputSnapshot;
import com.jobseekercopilot.cvcoverletter.dto.ProfileInputSnapshot;
import com.jobseekercopilot.cvcoverletter.dto.QualificationInput;
import com.jobseekercopilot.cvcoverletter.dto.QualificationStatus;
import com.jobseekercopilot.cvcoverletter.dto.RoleStatus;
import com.jobseekercopilot.cvcoverletter.dto.SnapshotProvenance;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class GenerationInputFixtures {

    private static final Instant CAPTURED_AT = Instant.parse("2026-07-24T12:00:00Z");
    public static final UUID PROFILE_REVISION_ID =
            UUID.fromString("60000000-0000-4000-8000-000000000001");
    public static final UUID CV_SKILL_FACT_ID =
            UUID.fromString("80000000-0000-4000-8000-000000000001");
    public static final UUID CV_PROJECT_FACT_ID =
            UUID.fromString("80000000-0000-4000-8000-000000000002");
    public static final UUID COVER_EXPERIENCE_FACT_ID =
            UUID.fromString("80000000-0000-4000-8000-000000000003");
    public static final UUID CV_PROJECT_TITLE_FACT_ID =
            UUID.fromString("80000000-0000-4000-8000-000000000004");

    private GenerationInputFixtures() {
    }

    public static GenerateRequest validRequest() {
        return new GenerateRequest("1.0", validProfile(), validJob());
    }

    public static GenerateRequest validVersionedRequest() {
        ProfileInputSnapshot profile = validProfile();
        profile.setProvenance(provenance(
                InputSourceOwner.USER_PROFILE_SERVICE,
                PROFILE_REVISION_ID.toString(),
                "sha256:" + "f".repeat(64)));
        profile.setSkills(new ArrayList<>());
        profile.setQualifications(new ArrayList<>());
        profile.setEmploymentHistory(new ArrayList<>());
        return new GenerateRequest(
                "2.0",
                profile,
                validJob(),
                new EvidenceSnapshotsInput(
                        evidenceSnapshot(
                                EvidenceSnapshotPurpose.CV,
                                "90000000-0000-4000-8000-000000000001",
                                EvidenceCategory.PROJECT,
                                List.of(
                                        fact(
                                                CV_PROJECT_TITLE_FACT_ID,
                                                "HEADING",
                                                "Job Seeker Copilot"),
                                        fact(
                                                CV_SKILL_FACT_ID,
                                                "DEMONSTRATED_SKILL",
                                                "Java"),
                                        fact(
                                                CV_PROJECT_FACT_ID,
                                                "DESCRIPTION",
                                                "Built useful services."))),
                        evidenceSnapshot(
                                EvidenceSnapshotPurpose.COVER_LETTER,
                                "90000000-0000-4000-8000-000000000002",
                                EvidenceCategory.VOLUNTEERING,
                                List.of(fact(
                                        COVER_EXPERIENCE_FACT_ID,
                                        "DESCRIPTION",
                                        "My experience includes building useful services.")))));
    }

    public static ProfileInputSnapshot validProfile() {
        return new ProfileInputSnapshot(
                provenance(InputSourceOwner.USER_PROFILE_SERVICE, "profile-123", "profile-v7"),
                new ContactInputSnapshot(
                        provenance(
                                InputSourceOwner.AUTHENTICATION_SERVICE,
                                "account-123",
                                "account-v3"),
                        "Alex Candidate",
                        "alex@example.com"),
                "London",
                new ArrayList<>(List.of("Java", "Spring")),
                new ArrayList<>(List.of("Backend Developer")),
                new ArrayList<>(List.of(new QualificationInput(
                        "BSc Computing",
                        "Example University",
                        QualificationStatus.COMPLETED,
                        "First",
                        "2024",
                        null))),
                new ArrayList<>(List.of(new EmploymentInput(
                        "Software Engineer",
                        "Example Ltd",
                        RoleStatus.CURRENT,
                        "2022-03",
                        null,
                        "Built and maintained Java services."))));
    }

    public static JobInputSnapshot validJob() {
        return new JobInputSnapshot(
                provenance(InputSourceOwner.JOB_SERVICE, "job-456", "job-v12"),
                "Java Developer",
                "Example Ltd",
                "Manchester",
                "Permanent",
                LocalDate.parse("2026-07-20"),
                "Build useful and reliable services.");
    }

    public static SnapshotProvenance provenance(
            InputSourceOwner owner, String resourceId, String version) {
        return new SnapshotProvenance(owner, resourceId, version, CAPTURED_AT);
    }

    private static EvidenceSnapshotInput evidenceSnapshot(
            EvidenceSnapshotPurpose purpose,
            String snapshotId,
            EvidenceCategory category,
            List<EvidenceSnapshotFactInput> facts) {
        return new EvidenceSnapshotInput(
                UUID.fromString(snapshotId),
                purpose,
                PROFILE_REVISION_ID,
                "f".repeat(64),
                new ArrayList<>(List.of(category)),
                new ArrayList<>(List.of(
                        new EvidenceSnapshotSelectionInput(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                2,
                                category,
                                "e".repeat(64),
                                new ArrayList<>(facts)))),
                purpose == EvidenceSnapshotPurpose.CV
                        ? "a".repeat(64)
                        : "b".repeat(64),
                CAPTURED_AT);
    }

    private static EvidenceSnapshotFactInput fact(
            UUID factId,
            String factType,
            String factValue) {
        return new EvidenceSnapshotFactInput(
                factId,
                factType,
                factValue,
                factValue.chars().anyMatch(Character::isDigit));
    }
}
