package com.jobseekercopilot.cvcoverletter.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jobseekercopilot.cvcoverletter.dto.ClaimDisposition;
import com.jobseekercopilot.cvcoverletter.dto.GeneratedApplicationDocuments;
import com.jobseekercopilot.cvcoverletter.dto.GeneratedClaim;
import com.jobseekercopilot.cvcoverletter.dto.GeneratedCv;
import com.jobseekercopilot.cvcoverletter.dto.GeneratedWorkHistory;
import com.jobseekercopilot.cvcoverletter.exception.InvalidLlmResponseException;
import com.jobseekercopilot.cvcoverletter.model.ApprovedEvidenceRecord;
import com.jobseekercopilot.cvcoverletter.model.ClaimEvidenceCatalog;
import com.jobseekercopilot.cvcoverletter.model.EvidencePurpose;
import com.jobseekercopilot.cvcoverletter.model.EvidenceSource;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class GeneratedDocumentQualityValidatorTest {
    private static final String SUMMARY =
            "Java software engineer with hands-on experience delivering Spring Boot APIs, "
                    + "automated tests and maintainable production services. Combines reliable "
                    + "backend development, collaborative product delivery and clear technical "
                    + "communication to support customer-facing systems, integrations and "
                    + "continuous improvement.";
    private static final List<String> BALANCED_BULLETS = List.of(
            "Developed Java and Spring Boot services that exposed reliable REST APIs for "
                    + "customer-facing product workflows and partner integrations.",
            "Implemented automated unit and integration tests to protect releases, clarify "
                    + "expected behaviour and improve maintainability across backend changes.",
            "Collaborated with product colleagues to translate requirements into scoped "
                    + "technical changes and communicate delivery trade-offs clearly to stakeholders.",
            "Maintained production software, investigated defects and delivered focused "
                    + "improvements through peer review, version control and repeatable release practices.");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GeneratedDocumentQualityValidator validator =
            new GeneratedDocumentQualityValidator();

    @Test
    void acceptsBalancedCvUsingStrongRoleRelevantEvidence() {
        QualityCase qualityCase = qualityCase(
                BALANCED_BULLETS,
                List.of("E.STRONG.JAVA", "E.STRONG.TEST", "E.STRONG.API"));

        assertDoesNotThrow(() -> validate(qualityCase));
    }

    @Test
    void rejectsCvThatIsSparseForSeveralAvailableEvidenceEntries() {
        QualityCase qualityCase = qualityCase(
                List.of(),
                List.of("E.STRONG.JAVA"));

        InvalidLlmResponseException error = assertThrows(
                InvalidLlmResponseException.class,
                () -> validate(qualityCase));

        assertTrue(error.getMessage().contains("CV is too sparse"),
                error.getMessage());
    }

    @Test
    void rejectsCvDominatedByOneNarrativeItem() {
        QualityCase qualityCase = qualityCase(
                List.of(
                        words("dominant", 50),
                        words("testing", 8),
                        words("delivery", 8),
                        words("support", 8)),
                List.of("E.STRONG.JAVA", "E.STRONG.TEST", "E.STRONG.API"));

        InvalidLlmResponseException error = assertThrows(
                InvalidLlmResponseException.class,
                () -> validate(qualityCase));

        assertTrue(error.getMessage().contains("imbalanced"),
                error.getMessage());
    }

    @Test
    void rejectsExactDuplicateCvBullets() {
        QualityCase qualityCase = qualityCase(
                List.of(
                        BALANCED_BULLETS.get(0),
                        BALANCED_BULLETS.get(0),
                        BALANCED_BULLETS.get(2),
                        BALANCED_BULLETS.get(3)),
                List.of("E.STRONG.JAVA", "E.STRONG.TEST", "E.STRONG.API"));

        InvalidLlmResponseException error = assertThrows(
                InvalidLlmResponseException.class,
                () -> validate(qualityCase));

        assertTrue(error.getMessage().contains("duplicate normalised"),
                error.getMessage());
    }

    @Test
    void rejectsCvThatOmitsAllTopRankedRoleEvidence() {
        QualityCase qualityCase = qualityCase(
                BALANCED_BULLETS,
                List.of("E.WEAK.ADMIN", "E.WEAK.STOCK"));

        InvalidLlmResponseException error = assertThrows(
                InvalidLlmResponseException.class,
                () -> validate(qualityCase));

        assertTrue(error.getMessage().contains(
                        "strongest role-relevant approved evidence"),
                error.getMessage());
    }

    private void validate(QualityCase qualityCase) {
        validator.validate(
                qualityCase.output(),
                qualityCase.documents(),
                catalog(),
                true,
                true);
    }

    private QualityCase qualityCase(
            List<String> bullets,
            List<String> citedEvidence) {
        GeneratedCv cv = new GeneratedCv();
        cv.setTitle("Java Software Engineer CV");
        cv.setTargetRole("Java Software Engineer");
        cv.setPersonalSummary(SUMMARY);
        cv.setCoreSkills(List.of());
        cv.setProjects(List.of());
        cv.setQualifications(List.of());
        GeneratedWorkHistory work = new GeneratedWorkHistory();
        work.setJobTitle("Software Engineer");
        work.setEmployer("Example Product Company");
        work.setStartDate("2022");
        work.setEndDate("2025");
        work.setTailoredDescription("");
        work.setResponsibilities(bullets);
        cv.setWorkHistory(bullets.isEmpty() ? List.of() : List.of(work));

        GeneratedApplicationDocuments documents =
                new GeneratedApplicationDocuments();
        documents.setCv(cv);
        documents.setClaims(IntStream.range(0, citedEvidence.size())
                .mapToObj(index -> claim(
                        "CLAIM-" + index,
                        citedEvidence.get(index),
                        "/cv/workHistory/0/responsibilities/"
                                + (index % Math.max(1, bullets.size()))))
                .toList());

        ObjectNode output = objectMapper.createObjectNode();
        ObjectNode cvOutput = output.putObject("cv");
        cvOutput.put("title", cv.getTitle());
        cvOutput.put("targetRole", cv.getTargetRole());
        cvOutput.put("personalSummary", cv.getPersonalSummary());
        cvOutput.putArray("coreSkills");
        cvOutput.putArray("projects");
        cvOutput.putArray("qualifications");
        ArrayNode histories = cvOutput.putArray("workHistory");
        if (!bullets.isEmpty()) {
            ObjectNode history = histories.addObject();
            history.put("tailoredDescription", "");
            ArrayNode responsibilities = history.putArray(
                    "responsibilities");
            bullets.forEach(responsibilities::add);
        }
        return new QualityCase(output, documents);
    }

    private GeneratedClaim claim(
            String claimId,
            String evidenceId,
            String path) {
        GeneratedClaim claim = new GeneratedClaim();
        claim.setClaimId(claimId);
        claim.setDisposition(ClaimDisposition.REWORDED);
        claim.setEvidenceIds(List.of(evidenceId));
        claim.setContentPaths(List.of(path));
        claim.setReviewText("");
        return claim;
    }

    private ClaimEvidenceCatalog catalog() {
        return new ClaimEvidenceCatalog("1.0", List.of(
                record("JOB.TITLE", "Java Software Engineer", "JOB_TITLE",
                        "JOB", EvidenceSource.JOB, "/job/title"),
                record("JOB.DESCRIPTION",
                        "Develop reliable Java and Spring Boot REST APIs, automated tests, "
                                + "partner integrations and maintainable production services.",
                        "DESCRIPTION", "JOB", EvidenceSource.JOB,
                        "/job/description"),
                record("E.STRONG.JAVA",
                        "Developed Java and Spring Boot REST API services.",
                        "RESPONSIBILITIES", "EMPLOYMENT",
                        EvidenceSource.EVIDENCE_SNAPSHOT,
                        "/selections/java/facts/0"),
                record("E.STRONG.TEST",
                        "Implemented automated unit and integration tests.",
                        "ACHIEVEMENTS", "EMPLOYMENT",
                        EvidenceSource.EVIDENCE_SNAPSHOT,
                        "/selections/testing/facts/0"),
                record("E.STRONG.API",
                        "Delivered reliable partner integrations in production.",
                        "ACHIEVEMENTS", "PROJECT",
                        EvidenceSource.EVIDENCE_SNAPSHOT,
                        "/selections/integrations/facts/0"),
                record("E.WEAK.ADMIN",
                        "Completed routine stock administration accurately.",
                        "RESPONSIBILITIES", "EMPLOYMENT",
                        EvidenceSource.EVIDENCE_SNAPSHOT,
                        "/selections/admin/facts/0"),
                record("E.WEAK.STOCK",
                        "Maintained stock records for a retail team.",
                        "RESPONSIBILITIES", "EMPLOYMENT",
                        EvidenceSource.EVIDENCE_SNAPSHOT,
                        "/selections/stock/facts/0")));
    }

    private ApprovedEvidenceRecord record(
            String id,
            String value,
            String factType,
            String category,
            EvidenceSource source,
            String path) {
        return new ApprovedEvidenceRecord(
                id, source, path, value, factType, category,
                EvidencePurpose.CV);
    }

    private String words(String prefix, int count) {
        return IntStream.range(0, count)
                .mapToObj(index -> prefix + index)
                .collect(java.util.stream.Collectors.joining(" "));
    }

    private record QualityCase(
            ObjectNode output,
            GeneratedApplicationDocuments documents) {
    }
}
