package com.jobseekercopilot.cvcoverletter.service;

import static com.jobseekercopilot.cvcoverletter.GenerationInputFixtures.validRequest;
import static com.jobseekercopilot.cvcoverletter.GenerationInputFixtures.validVersionedRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jobseekercopilot.cvcoverletter.model.ClaimEvidenceCatalog;
import com.jobseekercopilot.cvcoverletter.model.NormalizedGenerationInput;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ClaimEvidenceCatalogFactoryTest {

    @Test
    void createsStableApprovedRecordsWithoutIdentityOrProvenanceIdentifiers() {
        NormalizedGenerationInput input = new GenerationInputNormalizer(
                Clock.fixed(Instant.parse("2026-07-24T13:00:00Z"), ZoneOffset.UTC))
                .normalize("owner-secret", validRequest());
        ClaimEvidenceCatalogFactory factory = new ClaimEvidenceCatalogFactory();

        ClaimEvidenceCatalog first = factory.create(input);
        ClaimEvidenceCatalog second = factory.create(input);

        assertEquals(first, second);
        assertEquals("1.0", first.catalogVersion());
        assertEquals(20, first.records().size());
        assertTrue(first.records().stream().anyMatch(record ->
                record.evidenceId().equals("PROFILE.SKILL.1")
                        && record.sourcePath().equals("/profile/skills/0")
                        && record.value().equals("Java")));
        assertTrue(first.records().stream().anyMatch(record ->
                record.evidenceId().equals("PROFILE.EMPLOYMENT.1.JOB_TITLE")
                        && record.value().equals("Software Engineer")));
        assertTrue(first.records().stream().anyMatch(record ->
                record.evidenceId().equals("JOB.TITLE")
                        && record.value().equals("Java Developer")));
        String boundary = first.toString();
        assertFalse(boundary.contains("owner-secret"));
        assertFalse(boundary.contains("profile-123"));
        assertFalse(boundary.contains("profile-v7"));
        assertFalse(boundary.contains("job-456"));
        assertFalse(boundary.contains("job-v12"));
        assertFalse(boundary.contains("alex@example.com"));
        assertThrows(UnsupportedOperationException.class, () -> first.records().clear());
    }

    @Test
    void versionedCatalogUsesStableFactIdsAndNonTraditionalSections() {
        NormalizedGenerationInput input = new GenerationInputNormalizer(
                Clock.fixed(
                        Instant.parse("2026-07-24T13:00:00Z"),
                        ZoneOffset.UTC))
                .normalize("owner-secret", validVersionedRequest());

        ClaimEvidenceCatalog catalog =
                new ClaimEvidenceCatalogFactory().create(input);

        assertEquals("2.0", catalog.catalogVersion());
        assertFalse(catalog.toString().contains("PROFILE.SKILL."));
        assertEquals(
                java.util.List.of("PROJECT"),
                catalog.sectionOrder().get(
                        com.jobseekercopilot.cvcoverletter.model
                                .EvidencePurpose.CV));
        assertEquals(
                java.util.List.of("VOLUNTEERING"),
                catalog.sectionOrder().get(
                        com.jobseekercopilot.cvcoverletter.model
                                .EvidencePurpose.COVER_LETTER));
        assertTrue(catalog.records().stream().anyMatch(record ->
                record.evidenceId().equals(
                        com.jobseekercopilot.cvcoverletter
                                .GenerationInputFixtures
                                .CV_SKILL_FACT_ID.toString())
                        && "DEMONSTRATED_SKILL".equals(
                                record.factType())
                        && "PROJECT".equals(record.category())
                        && record.purpose()
                                == com.jobseekercopilot.cvcoverletter
                                        .model.EvidencePurpose.CV));
    }
}
