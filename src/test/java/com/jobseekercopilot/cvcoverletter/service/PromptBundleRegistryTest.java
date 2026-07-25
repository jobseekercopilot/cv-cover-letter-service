package com.jobseekercopilot.cvcoverletter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.cvcoverletter.config.PromptBundleProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

class PromptBundleRegistryTest {

    @Test
    void loadsAndVerifiesEveryPackagedApprovedBundle() {
        PromptBundleRegistry registry = registry("cv-cover-letter-1.2.0");

        assertEquals(
                java.util.Set.of(
                        "cv-cover-letter-1.0.0",
                        "cv-cover-letter-1.1.0",
                        "cv-cover-letter-1.2.0"),
                registry.approvedReleaseIds());
        assertEquals("ACTIVE", registry.selected().releaseStatus());
        assertEquals("1.2.0", registry.selected().metadata().bundleVersion());
        assertEquals("2.0.0", registry.selected().metadata().schemaVersion());
        assertEquals(64, registry.selected().metadata().evaluationPolicySha256().length());
        assertFalse(registry.selected().metadata().bundleSha256().isBlank());
    }

    @Test
    void comparesReviewedReleasesAndSupportsConfigurationRollback() {
        PromptBundleRegistry active = registry("cv-cover-letter-1.2.0");
        PromptBundleComparison comparison =
                active.compare("cv-cover-letter-1.1.0", "cv-cover-letter-1.2.0");

        assertFalse(comparison.templateChanged());
        assertTrue(comparison.rulesChanged());
        assertTrue(comparison.schemaChanged());
        assertFalse(comparison.evaluationPolicyChanged());

        PromptBundleRegistry rollback = registry("cv-cover-letter-1.1.0");
        assertEquals("ROLLBACK", rollback.selected().releaseStatus());
        assertEquals("1.1.0", rollback.selected().metadata().bundleVersion());
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
