package com.jobseekercopilot.cvcoverletter.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jobseekercopilot.cvcoverletter.dto.ClaimDisposition;
import com.jobseekercopilot.cvcoverletter.dto.GeneratedApplicationDocuments;
import com.jobseekercopilot.cvcoverletter.dto.GeneratedClaim;
import com.jobseekercopilot.cvcoverletter.dto.GeneratedCoverLetter;
import com.jobseekercopilot.cvcoverletter.dto.GeneratedCv;
import com.jobseekercopilot.cvcoverletter.dto.GeneratedWorkHistory;
import com.jobseekercopilot.cvcoverletter.model.ApplicationQualityReport;
import com.jobseekercopilot.cvcoverletter.model.ApprovedEvidenceRecord;
import com.jobseekercopilot.cvcoverletter.model.ClaimEvidenceCatalog;
import com.jobseekercopilot.cvcoverletter.model.EvidencePurpose;
import com.jobseekercopilot.cvcoverletter.model.EvidenceSource;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ApplicationQualityEvaluatorTest {

    private final ApplicationQualityEvaluator evaluator =
            new ApplicationQualityEvaluator();

    @Test
    void independentMetricsPreferSpecificTailoredDocumentsToInventoryCopy() {
        ClaimEvidenceCatalog catalog = catalog();
        ApplicationQualityReport strong = evaluator.evaluate(strongDocuments(), catalog);
        ApplicationQualityReport weak = evaluator.evaluate(weakDocuments(), catalog);

        assertTrue(strong.groundingPass());
        assertTrue(weak.groundingPass());
        assertTrue(strong.metrics().jdRelevance() > weak.metrics().jdRelevance());
        assertTrue(strong.metrics().strongEvidenceUtilisation()
                > weak.metrics().strongEvidenceUtilisation());
        assertTrue(strong.metrics().summarySpecificity()
                > weak.metrics().summarySpecificity());
        assertTrue(strong.metrics().cvCoverLetterComplementarity()
                > weak.metrics().cvCoverLetterComplementarity());
        assertTrue(strong.metrics().overallInterviewPersuasiveness()
                > weak.metrics().overallInterviewPersuasiveness());
    }

    @Test
    void groundingFailsWhenAnyClaimCitesEvidenceOutsideTheCatalogue() {
        GeneratedApplicationDocuments documents = strongDocuments();
        documents.getClaims().get(0).setEvidenceIds(List.of("E.NOT_APPROVED"));

        ApplicationQualityReport report = evaluator.evaluate(documents, catalog());

        assertFalse(report.groundingPass());
    }

    private GeneratedApplicationDocuments strongDocuments() {
        GeneratedCv cv = new GeneratedCv();
        cv.setTitle("Java Software Engineer CV");
        cv.setTargetRole("Java Software Engineer");
        cv.setPersonalSummary(
                "Software engineer with professional experience delivering Java and Spring Boot "
                        + "services and Angular interfaces for safety-critical railway products. "
                        + "Combines reliable API development, automated testing and collaborative "
                        + "product ownership to build maintainable customer-facing software.");
        GeneratedWorkHistory role = new GeneratedWorkHistory();
        role.setJobTitle("Full-Stack Software Developer");
        role.setEmployer("Fictional Transit Software Ltd");
        role.setStartDate("January 2021");
        role.setEndDate("August 2024");
        role.setResponsibilities(List.of(
                "Developed Java and Spring Boot microservices for safety-critical railway software.",
                "Built accessible Angular and TypeScript interfaces with product engineers.",
                "Implemented automated unit and integration tests for reliable releases.",
                "Mentored junior developers and explained technical decisions to stakeholders."));
        cv.setWorkHistory(List.of(role));
        cv.setProjects(List.of());
        cv.setQualifications(List.of());
        cv.setCoreSkills(List.of());

        GeneratedCoverLetter cover = new GeneratedCoverLetter();
        cover.setTitle("Java Software Engineer Cover Letter");
        cover.setJobTitle("Java Software Engineer");
        cover.setCompanyName("Fictional Product Company");
        cover.setGreeting("Dear Hiring Manager");
        cover.setOpeningParagraph("Please consider my application for this role.");
        cover.setBodyParagraphs(List.of(
                "Your focus on reliable partner-facing services matches my experience translating "
                        + "product requirements into maintainable Java APIs.",
                "In a professional railway team, I worked across backend and frontend boundaries, "
                        + "using testing and peer collaboration to protect software quality.",
                "I would bring that hands-on delivery approach and clear stakeholder communication "
                        + "to the platform improvements described in the vacancy."));
        cover.setClosingParagraph("Thank you for considering my application.");
        cover.setSignOff("Yours faithfully");

        GeneratedApplicationDocuments documents = new GeneratedApplicationDocuments();
        documents.setCv(cv);
        documents.setCoverLetter(cover);
        documents.setClaims(List.of(
                claim("E.JAVA"), claim("E.ANGULAR"), claim("E.TEST"),
                claim("E.COLLABORATION"), claim("E.RAIL")));
        return documents;
    }

    private GeneratedApplicationDocuments weakDocuments() {
        String repeated = "Maintained canonical system data personas, fixture seed data, "
                + "internal service gateway inventory and waitlist foundations.";
        GeneratedCv cv = new GeneratedCv();
        cv.setTitle("Java Software Engineer CV");
        cv.setTargetRole("Java Software Engineer");
        cv.setPersonalSummary("This document contains verified skills and canonical system data.");
        GeneratedWorkHistory role = new GeneratedWorkHistory();
        role.setJobTitle("Product Builder");
        role.setEmployer("Fictional Platform Lab");
        role.setStartDate("May 2026");
        role.setResponsibilities(List.of(repeated));
        cv.setWorkHistory(List.of(role));
        cv.setProjects(List.of());
        cv.setQualifications(List.of());
        cv.setCoreSkills(List.of());

        GeneratedCoverLetter cover = new GeneratedCoverLetter();
        cover.setBodyParagraphs(List.of(repeated, repeated, repeated));
        GeneratedApplicationDocuments documents = new GeneratedApplicationDocuments();
        documents.setCv(cv);
        documents.setCoverLetter(cover);
        documents.setClaims(List.of(claim("E.INVENTORY")));
        return documents;
    }

    private ClaimEvidenceCatalog catalog() {
        List<ApprovedEvidenceRecord> records = new ArrayList<>();
        records.add(record("JOB.TITLE", "Java Software Engineer", "JOB_TITLE", "JOB", EvidenceSource.JOB));
        records.add(record("JOB.DESCRIPTION",
                "Build reliable Java and Spring Boot partner APIs, automated tests and product improvements.",
                "DESCRIPTION", "JOB", EvidenceSource.JOB));
        records.add(record("E.JAVA",
                "Developed Java and Spring Boot microservices.",
                "RESPONSIBILITIES", "EMPLOYMENT", EvidenceSource.EVIDENCE_SNAPSHOT));
        records.add(record("E.ANGULAR",
                "Built Angular and TypeScript interfaces.",
                "RESPONSIBILITIES", "EMPLOYMENT", EvidenceSource.EVIDENCE_SNAPSHOT));
        records.add(record("E.TEST",
                "Implemented automated unit and integration tests.",
                "ACHIEVEMENTS", "EMPLOYMENT", EvidenceSource.EVIDENCE_SNAPSHOT));
        records.add(record("E.COLLABORATION",
                "Collaborated with engineers and mentored junior developers.",
                "RESPONSIBILITIES", "EMPLOYMENT", EvidenceSource.EVIDENCE_SNAPSHOT));
        records.add(record("E.RAIL",
                "Delivered safety-critical railway software.",
                "ACHIEVEMENTS", "EMPLOYMENT", EvidenceSource.EVIDENCE_SNAPSHOT));
        records.add(record("E.INVENTORY",
                "Maintained canonical system data personas, fixture seed data, internal service "
                        + "gateway inventory and waitlist foundations.",
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

    private GeneratedClaim claim(String evidenceId) {
        GeneratedClaim claim = new GeneratedClaim();
        claim.setClaimId("CLAIM-" + evidenceId);
        claim.setDisposition(ClaimDisposition.REWORDED);
        claim.setEvidenceIds(List.of(evidenceId));
        claim.setContentPaths(List.of("/fictional/path"));
        claim.setReviewText("");
        return claim;
    }
}
