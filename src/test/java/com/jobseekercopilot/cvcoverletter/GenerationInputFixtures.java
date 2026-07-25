package com.jobseekercopilot.cvcoverletter;

import com.jobseekercopilot.cvcoverletter.dto.ContactInputSnapshot;
import com.jobseekercopilot.cvcoverletter.dto.EmploymentInput;
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

public final class GenerationInputFixtures {

    private static final Instant CAPTURED_AT = Instant.parse("2026-07-24T12:00:00Z");

    private GenerationInputFixtures() {
    }

    public static GenerateRequest validRequest() {
        return new GenerateRequest("1.0", validProfile(), validJob());
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
}
