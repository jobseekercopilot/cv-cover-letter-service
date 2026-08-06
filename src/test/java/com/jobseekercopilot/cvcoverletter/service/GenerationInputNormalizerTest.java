package com.jobseekercopilot.cvcoverletter.service;

import static com.jobseekercopilot.cvcoverletter.GenerationInputFixtures.validRequest;
import static com.jobseekercopilot.cvcoverletter.GenerationInputFixtures.validVersionedRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jobseekercopilot.cvcoverletter.dto.EmploymentInput;
import com.jobseekercopilot.cvcoverletter.dto.InputSourceOwner;
import com.jobseekercopilot.cvcoverletter.dto.InputWarning;
import com.jobseekercopilot.cvcoverletter.dto.RoleStatus;
import com.jobseekercopilot.cvcoverletter.exception.InvalidGenerationInputException;
import com.jobseekercopilot.cvcoverletter.model.NormalizedGenerationInput;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class GenerationInputNormalizerTest {

    private final GenerationInputNormalizer normalizer = new GenerationInputNormalizer(
            Clock.fixed(Instant.parse("2026-07-24T13:00:00Z"), ZoneOffset.UTC));

    @Test
    void normalizesMarkupDuplicatesAndConflictsWithoutInventingHistory() {
        var request = validRequest();
        request.getProfile().setSkills(new ArrayList<>(List.of(
                "<b>Java</b>",
                " java ",
                "&lt;script&gt;discard()&lt;/script&gt;Spring",
                "&lt;style&gt;discard the remainder")));
        EmploymentInput first = request.getProfile().getEmploymentHistory().get(0);
        request.getProfile().getEmploymentHistory().add(new EmploymentInput(
                first.getJobTitle(),
                first.getEmployer(),
                RoleStatus.CURRENT,
                first.getStartDate(),
                "2025-01",
                "Conflicting replacement"));

        NormalizedGenerationInput actual = normalizer.normalize(" owner-123 ", request);

        assertEquals("owner-123", actual.ownerId());
        assertEquals(List.of("Java", "Spring"), actual.profile().skills());
        assertEquals(1, actual.profile().employmentHistory().size());
        assertEquals(
                "Built and maintained Java services.",
                actual.profile().employmentHistory().get(0).responsibilities());
        assertTrue(codes(actual).contains("INPUT_TEXT_NORMALIZED"));
        assertTrue(codes(actual).contains("PROFILE_SKILL_DUPLICATE_REMOVED"));
        assertTrue(codes(actual).contains("PROFILE_EMPLOYMENT_DATE_CONFLICT"));
        assertTrue(codes(actual).contains("PROFILE_EMPLOYMENT_CONFLICT"));
        assertFalse(actual.profile().toString().toLowerCase().contains("script"));
    }

    @Test
    void rejectsInvalidAndReversedEmploymentDates() {
        var invalid = validRequest();
        invalid.getProfile().getEmploymentHistory().get(0).setStartDate("March 2022");
        assertThrows(
                InvalidGenerationInputException.class,
                () -> normalizer.normalize("owner-123", invalid));

        var reversed = validRequest();
        EmploymentInput employment = reversed.getProfile().getEmploymentHistory().get(0);
        employment.setStatus(RoleStatus.PREVIOUS_ROLE);
        employment.setStartDate("2024-04");
        employment.setEndDate("2023-12");
        assertThrows(
                InvalidGenerationInputException.class,
                () -> normalizer.normalize("owner-123", reversed));
    }

    @Test
    void reportsMissingEvidenceInDeterministicFieldOrder() {
        var request = validRequest();
        request.getProfile().setContact(null);
        request.getProfile().setSkills(List.of());
        request.getProfile().setTargetRoles(List.of());
        request.getProfile().setQualifications(List.of());
        request.getProfile().setEmploymentHistory(List.of());

        NormalizedGenerationInput first = normalizer.normalize("owner-123", request);
        NormalizedGenerationInput second = normalizer.normalize("owner-123", request);

        assertEquals(first.warnings(), second.warnings());
        assertEquals(
                List.of(
                        "CONTACT_NAME_MISSING",
                        "CONTACT_EMAIL_MISSING",
                        "PROFILE_SKILLS_MISSING",
                        "PROFILE_TARGET_ROLES_MISSING",
                        "PROFILE_QUALIFICATIONS_MISSING",
                        "PROFILE_EMPLOYMENT_HISTORY_MISSING"),
                codes(first));
    }

    @Test
    void rejectsWrongProvenanceOwnerAndFutureSnapshot() {
        var wrongOwner = validRequest();
        wrongOwner.getJob().getProvenance().setOwner(InputSourceOwner.USER_PROFILE_SERVICE);
        assertThrows(
                InvalidGenerationInputException.class,
                () -> normalizer.normalize("owner-123", wrongOwner));

        var future = validRequest();
        future.getProfile().getProvenance().setCapturedAt(
                Instant.parse("2026-07-24T13:06:00Z"));
        assertThrows(
                InvalidGenerationInputException.class,
                () -> normalizer.normalize("owner-123", future));
    }

    @Test
    void rejectsInputAboveAggregatePromptLimit() {
        var request = validRequest();
        List<EmploymentInput> employment = new ArrayList<>();
        for (int index = 0; index < 11; index++) {
            employment.add(new EmploymentInput(
                    "Role " + index,
                    "Employer " + index,
                    RoleStatus.PREVIOUS_ROLE,
                    "2020",
                    "2021",
                    "x".repeat(4000)));
        }
        request.getProfile().setEmploymentHistory(employment);

        InvalidGenerationInputException exception = assertThrows(
                InvalidGenerationInputException.class,
                () -> normalizer.normalize("owner-123", request));
        assertTrue(exception.getMessage().contains("40000"));
    }

    @Test
    void acceptsExactPurposeBoundSnapshotsAndPreservesClaimantOrder() {
        var request = validVersionedRequest();

        NormalizedGenerationInput actual =
                normalizer.normalize("owner-123", request);

        assertEquals("2.0", actual.inputSchemaVersion());
        assertEquals(
                List.of(
                        com.jobseekercopilot.cvcoverletter.dto
                                .EvidenceCategory.PROJECT),
                actual.evidenceSnapshots().cv().sectionOrder());
        assertEquals(
                "HEADING",
                actual.evidenceSnapshots().cv()
                        .selections().get(0)
                        .facts().get(0).factType());
        assertEquals(
                "DEMONSTRATED_SKILL",
                actual.evidenceSnapshots().cv()
                        .selections().get(0)
                        .facts().get(1).factType());
        assertTrue(actual.profile().skills().isEmpty());
        assertTrue(actual.profile().employmentHistory().isEmpty());
    }

    @Test
    void versionedFlowRejectsRawFactsMismatchedProfileAndWrongPurpose() {
        var rawFacts = validVersionedRequest();
        rawFacts.getProfile().setSkills(List.of("Browser supplied"));
        assertThrows(
                InvalidGenerationInputException.class,
                () -> normalizer.normalize("owner-123", rawFacts));

        var mismatchedProfile = validVersionedRequest();
        mismatchedProfile.getEvidenceSnapshots().getCv()
                .setProfileContentDigest("0".repeat(64));
        assertThrows(
                InvalidGenerationInputException.class,
                () -> normalizer.normalize(
                        "owner-123", mismatchedProfile));

        var wrongPurpose = validVersionedRequest();
        wrongPurpose.getEvidenceSnapshots().getCoverLetter()
                .setPurpose(com.jobseekercopilot.cvcoverletter.dto
                        .EvidenceSnapshotPurpose.CV);
        assertThrows(
                InvalidGenerationInputException.class,
                () -> normalizer.normalize(
                        "owner-123", wrongPurpose));
    }

    private List<String> codes(NormalizedGenerationInput input) {
        return input.warnings().stream().map(InputWarning::code).toList();
    }
}
