package com.jobseekercopilot.cvcoverletter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.cvcoverletter.config.PromptBundleProperties;
import com.jobseekercopilot.cvcoverletter.dto.ClaimDisposition;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

class PromptBundleRegistryTest {

    @Test
    void loadsAndVerifiesEveryPackagedApprovedBundle() {
        PromptBundleRegistry registry = registry("cv-cover-letter-1.7.1");

        assertEquals(
                java.util.Set.of(
                        "cv-cover-letter-1.0.0",
                        "cv-cover-letter-1.1.0",
                        "cv-cover-letter-1.2.0",
                        "cv-cover-letter-1.3.0",
                        "cv-cover-letter-1.4.0",
                        "cv-cover-letter-1.5.0",
                        "cv-cover-letter-1.5.1",
                        "cv-cover-letter-1.5.2",
                        "cv-cover-letter-1.5.3",
                        "cv-cover-letter-1.5.4",
                        "cv-cover-letter-1.5.5",
                        "cv-cover-letter-1.5.6",
                        "cv-cover-letter-1.5.8",
                        "cv-cover-letter-1.5.9",
                        "cv-cover-letter-1.5.10",
                        "cv-cover-letter-1.5.11",
                        "cv-cover-letter-1.6.0",
                        "cv-cover-letter-1.6.1",
                        "cv-cover-letter-1.7.0",
                        "cv-cover-letter-1.7.1"),
                registry.approvedReleaseIds());
        assertEquals("ACTIVE", registry.selected().releaseStatus());
        assertEquals("1.7.1", registry.selected().metadata().bundleVersion());
        assertEquals("4.0.2", registry.selected().metadata().schemaVersion());
        assertEquals(64, registry.selected().metadata().evaluationPolicySha256().length());
        assertFalse(registry.selected().metadata().bundleSha256().isBlank());
    }

    @Test
    void comparesReviewedReleasesAndSupportsConfigurationRollback() {
        PromptBundleRegistry active = registry("cv-cover-letter-1.7.1");
        PromptBundleComparison comparison =
                active.compare("cv-cover-letter-1.6.1", "cv-cover-letter-1.7.0");

        assertFalse(comparison.templateChanged());
        assertTrue(comparison.rulesChanged());
        assertTrue(comparison.schemaChanged());
        assertTrue(comparison.evaluationPolicyChanged());

        PromptBundleComparison hardening =
                active.compare("cv-cover-letter-1.7.0", "cv-cover-letter-1.7.1");

        assertFalse(hardening.templateChanged());
        assertTrue(hardening.rulesChanged());
        assertFalse(hardening.schemaChanged());
        assertFalse(hardening.evaluationPolicyChanged());

        PromptBundleRegistry rollback = registry("cv-cover-letter-1.5.6");
        assertEquals("ROLLBACK", rollback.selected().releaseStatus());
        assertEquals("1.5.6", rollback.selected().metadata().bundleVersion());
        assertEquals("3.7.0", rollback.selected().metadata().schemaVersion());

        assertThrows(
                IllegalStateException.class,
                () -> registry("cv-cover-letter-1.5.7"));
    }

    @Test
    void keepsHistoricalReviewDispositionsInThePublishedLedgerContract() {
        assertEquals(
                java.util.Set.of(
                        ClaimDisposition.SUPPORTED,
                        ClaimDisposition.REWORDED,
                        ClaimDisposition.CONFIRMATION_REQUIRED,
                        ClaimDisposition.REJECTED),
                java.util.Set.of(ClaimDisposition.values()));
    }

    @Test
    void failsClosedWhenConfigurationSelectsAnUnapprovedBundle() {
        IllegalStateException error = assertThrows(
                IllegalStateException.class, () -> registry("../../unreviewed"));

        assertTrue(error.getMessage().contains("not in the packaged approved index")
                || error.getMessage().contains("release ID is invalid"));
    }

    private PromptBundleRegistry registry(String releaseId) {
        PromptBundleProperties properties = new PromptBundleProperties();
        properties.setSelectedReleaseId(releaseId);
        PromptBundleRegistry registry = new PromptBundleRegistry(
                new ObjectMapper().findAndRegisterModules(),
                new DefaultResourceLoader(),
                properties);
        registry.initialize();
        return registry;
    }
}
