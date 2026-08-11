package com.jobseekercopilot.cvcoverletter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jobseekercopilot.cvcoverletter.model.ApplicationQualityPlan;
import com.jobseekercopilot.cvcoverletter.model.ApprovedEvidenceRecord;
import com.jobseekercopilot.cvcoverletter.model.ClaimEvidenceCatalog;
import com.jobseekercopilot.cvcoverletter.model.EvidencePurpose;
import com.jobseekercopilot.cvcoverletter.model.EvidenceSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ApplicationQualityPlannerTest {

    private final ApplicationQualityPlanner planner =
            new ApplicationQualityPlanner();

    @Test
    void materiallyDifferentVacanciesProduceDifferentPrioritiesAndTopEvidence() {
        Map<String, String> vacancies = new LinkedHashMap<>();
        vacancies.put("Java Backend Developer",
                "Build reliable Java and Spring Boot REST APIs and partner integrations.");
        vacancies.put("Angular Developer",
                "Build accessible Angular and TypeScript customer interfaces.");
        vacancies.put("Junior Cloud Engineer",
                "Learn AWS, Linux, Docker and CI/CD while supporting deployments.");
        vacancies.put("Application Support Developer",
                "Troubleshoot incidents, support customers and improve Java services.");
        vacancies.put("Full-Stack Product Engineer",
                "Take ownership of full-stack product delivery and collaborate with product teams.");
        vacancies.put("Software Quality Engineer",
                "Develop automated unit, integration and end-to-end tests.");
        vacancies.put("Junior Software Engineer",
                "Apply engineering fundamentals, learn quickly and collaborate well.");

        Map<String, String> topByRole = new LinkedHashMap<>();
        Map<String, List<String>> conceptsByRole = new LinkedHashMap<>();
        vacancies.forEach((title, description) -> {
            ApplicationQualityPlan plan = planner.plan(catalog(title, description));
            topByRole.put(title, plan.rankedEvidence().get(0).evidenceId());
            conceptsByRole.put(title, plan.vacancyEmphasis().stream()
                    .map(ApplicationQualityPlan.VacancyEmphasis::concept)
                    .toList());
        });

        assertEquals("E.JAVA", topByRole.get("Java Backend Developer"));
        assertEquals("E.ANGULAR", topByRole.get("Angular Developer"));
        assertEquals("E.CLOUD", topByRole.get("Junior Cloud Engineer"));
        assertEquals("E.SUPPORT", topByRole.get("Application Support Developer"));
        assertEquals("E.TEST", topByRole.get("Software Quality Engineer"));
        assertTrue(topByRole.values().stream().distinct().count() >= 5);
        assertNotEquals(
                conceptsByRole.get("Java Backend Developer"),
                conceptsByRole.get("Angular Developer"));
        assertTrue(conceptsByRole.get("Full-Stack Product Engineer")
                .contains("OWNERSHIP_AUTONOMY"));
        assertTrue(conceptsByRole.get("Junior Software Engineer")
                .contains("JUNIOR_GROWTH"));
    }

    @Test
    void professionalDeliveryOutranksVerboseInternalInventory() {
        ClaimEvidenceCatalog catalog = catalog(
                "Software Engineer",
                "Deliver Java services, automated testing and useful product improvements.");
        ApplicationQualityPlan plan = planner.plan(catalog);

        int delivery = score(plan, "E.JAVA");
        int inventory = score(plan, "E.INVENTORY");
        assertTrue(delivery > inventory + 20);
        assertTrue(plan.rankedEvidence().stream()
                .filter(item -> item.evidenceId().equals("E.INVENTORY"))
                .flatMap(item -> item.reasons().stream())
                .anyMatch(reason -> reason.contains("low hiring-decision signal")));
    }

    @Test
    void planIsGuidanceAndNeverAddsCandidateFacts() {
        ApplicationQualityPlan plan = planner.plan(catalog(
                "Python Developer", "Build Python and FastAPI services."));

        assertEquals(ApplicationQualityPlanner.VERSION, plan.version());
        assertTrue(plan.vacancyEmphasis().stream()
                .anyMatch(item -> item.concept().equals("PYTHON_ENGINEERING")));
        assertFalse(plan.rankedEvidence().stream()
                .anyMatch(item -> item.evidenceId().startsWith("JOB.")));
        assertFalse(plan.rankedEvidence().stream()
                .anyMatch(item -> item.evidenceId().startsWith("REQUEST.")));
    }

    private ClaimEvidenceCatalog catalog(String title, String description) {
        List<ApprovedEvidenceRecord> records = new ArrayList<>();
        records.add(record("JOB.TITLE", title, "JOB_TITLE", "JOB", EvidenceSource.JOB));
        records.add(record("JOB.DESCRIPTION", description, "DESCRIPTION", "JOB", EvidenceSource.JOB));
        records.add(record("E.JAVA",
                "Developed reliable Java and Spring Boot REST APIs and integrated partner systems.",
                "RESPONSIBILITIES", "EMPLOYMENT", EvidenceSource.EVIDENCE_SNAPSHOT));
        records.add(record("E.ANGULAR",
                "Built accessible Angular and TypeScript customer interfaces.",
                "ACHIEVEMENTS", "EMPLOYMENT", EvidenceSource.EVIDENCE_SNAPSHOT));
        records.add(record("E.CLOUD",
                "Deployed Docker services using AWS, Linux and CI/CD workflows.",
                "ACHIEVEMENTS", "PROJECT", EvidenceSource.EVIDENCE_SNAPSHOT));
        records.add(record("E.SUPPORT",
                "Resolved application incidents and supported customers using Java services.",
                "RESPONSIBILITIES", "EMPLOYMENT", EvidenceSource.EVIDENCE_SNAPSHOT));
        records.add(record("E.TEST",
                "Implemented automated unit, integration and end-to-end tests.",
                "ACHIEVEMENTS", "EMPLOYMENT", EvidenceSource.EVIDENCE_SNAPSHOT));
        records.add(record("E.OWNERSHIP",
                "Owned full-stack product delivery and collaborated with product stakeholders.",
                "ACHIEVEMENTS", "PROJECT", EvidenceSource.EVIDENCE_SNAPSHOT));
        records.add(record("E.LEARNING",
                "Supported junior engineers through mentoring and collaborative problem solving.",
                "RESPONSIBILITIES", "EMPLOYMENT", EvidenceSource.EVIDENCE_SNAPSHOT));
        records.add(record("E.INVENTORY",
                "Maintained canonical system data personas, fixture seed data, internal service "
                        + "gateway inventory, waitlist foundations and AI-credit architecture "
                        + "across many internal subsystems for the platform.",
                "DESCRIPTION", "PROJECT", EvidenceSource.EVIDENCE_SNAPSHOT));
        return new ClaimEvidenceCatalog("2.0", records);
    }

    private ApprovedEvidenceRecord record(
            String id,
            String value,
            String factType,
            String category,
            EvidenceSource source
    ) {
        return new ApprovedEvidenceRecord(
                id, source, "/fictional/" + id, value, factType, category,
                EvidencePurpose.BOTH);
    }

    private int score(ApplicationQualityPlan plan, String id) {
        return plan.rankedEvidence().stream()
                .filter(item -> item.evidenceId().equals(id))
                .findFirst()
                .orElseThrow()
                .score();
    }
}
