package com.jobseekercopilot.cvcoverletter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jobseekercopilot.cvcoverletter.GenerationInputFixtures;
import com.jobseekercopilot.cvcoverletter.dto.ClaimDisposition;
import com.jobseekercopilot.cvcoverletter.dto.GeneratedApplicationDocuments;
import com.jobseekercopilot.cvcoverletter.dto.GeneratedClaim;
import com.jobseekercopilot.cvcoverletter.exception.InvalidLlmResponseException;
import com.jobseekercopilot.cvcoverletter.model.ApprovedEvidenceRecord;
import com.jobseekercopilot.cvcoverletter.model.ClaimEvidenceCatalog;
import com.jobseekercopilot.cvcoverletter.model.EvidencePurpose;
import com.jobseekercopilot.cvcoverletter.model.EvidenceSource;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClaimEvidenceValidatorAdaptiveTest {

    private ObjectMapper objectMapper;
    private ClaimEvidenceValidator validator;
    private LlmResponseParser parser;
    private JsonNode schema;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        validator = new ClaimEvidenceValidator();
        parser = new LlmResponseParser(
                objectMapper,
                validator,
                new GeneratedDocumentQualityValidator());
        try (InputStream input = getClass().getResourceAsStream(
                "/prompts/bundles/cv-cover-letter-1.5.9/output-schema.json")) {
            if (input == null) {
                throw new IllegalStateException(
                        "Active adaptive schema fixture is missing.");
            }
            schema = objectMapper.readTree(input);
        }
    }

    @Test
    void acceptsTheLiveMissingCoverageShapeAndClearsBothOptionalDescriptions()
            throws Exception {
        ObjectNode output = activeOutput();
        ArrayNode histories = (ArrayNode) output.at("/cv/workHistory");
        histories.add(workHistory("Unsupported tailored narrative one.", true));
        histories.add(workHistory("Unsupported tailored narrative two.", false));
        ArrayNode claims = (ArrayNode) output.path("claims");
        claims.add(claim(
                "CLAIM-101",
                List.of("PROFILE.EMPLOYMENT.1.JOB_TITLE"),
                List.of(
                        "/cv/workHistory/0/jobTitle",
                        "/cv/workHistory/1/jobTitle")));
        claims.add(claim(
                "CLAIM-102",
                List.of("PROFILE.EMPLOYMENT.1.EMPLOYER"),
                List.of(
                        "/cv/workHistory/0/employer",
                        "/cv/workHistory/1/employer")));
        claims.add(claim(
                "CLAIM-103",
                List.of("PROFILE.EMPLOYMENT.1.START_DATE"),
                List.of(
                        "/cv/workHistory/0/startDate",
                        "/cv/workHistory/1/startDate")));
        claims.add(claim(
                "CLAIM-104",
                List.of("PROFILE.EMPLOYMENT.1.RESPONSIBILITIES"),
                List.of("/cv/workHistory/0/responsibilities/0")));

        GeneratedApplicationDocuments accepted = parser.parse(
                objectMapper.writeValueAsString(output),
                schema,
                baseCatalog());

        assertEquals("", accepted.getCv().getWorkHistory().get(0)
                .getTailoredDescription());
        assertEquals("", accepted.getCv().getWorkHistory().get(1)
                .getTailoredDescription());
        assertEquals(1, coverageCount(accepted, "/cv/title"));
        assertEquals(1, coverageCount(accepted, "/coverLetter/title"));
        assertEquals(1, coverageCount(accepted, "/cv/personalSummary"));
        assertTrue(claimFor(accepted, "/cv/title").getContentPaths()
                .contains("/cv/targetRole"));
        assertTrue(claimFor(accepted, "/coverLetter/title").getContentPaths()
                .contains("/coverLetter/jobTitle"));
        assertTrue(accepted.getClaims().stream()
                .filter(claim -> claim.getContentPaths() != null)
                .flatMap(claim -> claim.getContentPaths().stream())
                .noneMatch(path -> path.endsWith("/tailoredDescription")));
        assertTrue(accepted.getClaims().size() <= 40);
    }

    @Test
    void dropsFinalClaimsThatOnlyReferenceEmptyOptionalContent()
            throws Exception {
        ObjectNode output = activeOutput();
        ArrayNode histories = (ArrayNode) output.at("/cv/workHistory");
        histories.add(workHistory("", true));
        ArrayNode claims = (ArrayNode) output.path("claims");
        claims.add(claim(
                "CLAIM-801",
                List.of("PROFILE.EMPLOYMENT.1.JOB_TITLE"),
                List.of("/cv/workHistory/0/tailoredDescription")));

        GeneratedApplicationDocuments accepted = parser.parse(
                objectMapper.writeValueAsString(output),
                schema,
                baseCatalog());

        assertTrue(accepted.getClaims().stream()
                .noneMatch(claim -> "CLAIM-801"
                        .equals(claim.getClaimId())));
        assertEquals("", accepted.getCv().getWorkHistory().get(0)
                .getTailoredDescription());
    }

    @Test
    void retainsPopulatedPathsWhenAClaimAlsoReferencesEmptyOptionalContent()
            throws Exception {
        ObjectNode output = activeOutput();
        ArrayNode histories = (ArrayNode) output.at("/cv/workHistory");
        histories.add(workHistory("", true));
        ArrayNode claims = (ArrayNode) output.path("claims");
        claims.add(claim(
                "CLAIM-802",
                List.of("PROFILE.EMPLOYMENT.1.JOB_TITLE"),
                List.of(
                        "/cv/workHistory/0/jobTitle",
                        "/cv/workHistory/0/tailoredDescription")));

        GeneratedApplicationDocuments accepted = parser.parse(
                objectMapper.writeValueAsString(output),
                schema,
                baseCatalog());

        GeneratedClaim normalized = accepted.getClaims().stream()
                .filter(claim -> "CLAIM-802"
                        .equals(claim.getClaimId()))
                .findFirst()
                .orElseThrow();
        assertEquals(
                List.of("/cv/workHistory/0/jobTitle"),
                normalized.getContentPaths());
    }

    @Test
    void projectsAllTwelveSkillsWithTheExpandedClaimCapacity()
            throws Exception {
        CapacityFixture fixture = capacityFixture();
        GeneratedApplicationDocuments documents = parseWithoutValidation(
                fixture.output());

        validator.validate(
                fixture.output(),
                documents,
                fixture.catalog(),
                true,
                true,
                true);

        assertEquals(44, documents.getClaims().size());
        assertEquals(
                12,
                projectedSkillClaims(documents).size(),
                documents.getClaims().stream()
                        .map(claim -> claim.getClaimId()
                                + "=" + claim.getContentPaths())
                        .toList()
                        .toString());
        assertEquals(12, fixture.output().at("/cv/coreSkills").size());
        assertTrue(projectedSkillClaims(documents).stream()
                .allMatch(claim -> claim.getEvidenceIds().size() == 1
                        && claim.getContentPaths().size() == 1));
    }

    @Test
    void projectsTwelveWhenTheNormalizedNonSkillLedgerHasRoom()
            throws Exception {
        ObjectNode output = activeOutput();
        ClaimEvidenceCatalog catalog = withTwelveSkills(baseCatalog());
        proposeTwelveSkills(output);
        GeneratedApplicationDocuments documents = parseWithoutValidation(output);

        validator.validate(
                output,
                documents,
                catalog,
                true,
                true,
                true);

        assertEquals(12, projectedSkillClaims(documents).size());
        assertEquals(12, output.at("/cv/coreSkills").size());
        assertTrue(documents.getClaims().size() < 40);
    }

    @Test
    void rejectsPersonalSummaryGroundedOnlyInJobEvidence()
            throws Exception {
        ObjectNode output = activeOutput();
        ArrayNode evidenceIds = (ArrayNode) output.at(
                "/personalSummaryClaim/evidenceIds");
        evidenceIds.removeAll();
        evidenceIds.add("JOB.DESCRIPTION");

        InvalidLlmResponseException error = assertThrows(
                InvalidLlmResponseException.class,
                () -> parser.parse(
                        objectMapper.writeValueAsString(output),
                        schema,
                        baseCatalog()));

        assertTrue(error.getMessage().contains(
                "candidate claim has no confirmed claimant evidence"));
    }

    @Test
    void titleLikeClaimantEvidenceCannotBypassCanonicalJobTitleProvenance()
            throws Exception {
        ObjectNode output = activeOutput();
        ClaimEvidenceCatalog base = baseCatalog();
        List<ApprovedEvidenceRecord> records = new ArrayList<>(base.records());
        records.add(new ApprovedEvidenceRecord(
                "PROFILE.SUMMARY.TITLELIKE",
                EvidenceSource.PROFILE,
                "/profile/about/titleLike",
                "Java Developer CV",
                "NARRATIVE",
                "PROFILE_SUMMARY",
                EvidencePurpose.CV));
        ClaimEvidenceCatalog catalog = new ClaimEvidenceCatalog(
                base.catalogVersion(),
                List.copyOf(records),
                base.sectionOrder());

        GeneratedApplicationDocuments accepted = parser.parse(
                objectMapper.writeValueAsString(output),
                schema,
                catalog);

        GeneratedClaim titleClaim = claimFor(accepted, "/cv/title");
        assertTrue(titleClaim.getEvidenceIds().contains("JOB.TITLE"));
        assertTrue(titleClaim.getContentPaths().contains("/cv/targetRole"));
        assertEquals(1, coverageCount(accepted, "/cv/title"));
    }

    @Test
    void rollbackPolicyPreservesEightSkillsAtTheClaimBoundary()
            throws Exception {
        CapacityFixture fixture = capacityFixture();
        ArrayNode proposed = (ArrayNode) fixture.output()
                .at("/cv/coreSkills");
        proposed.removeAll();
        ObjectNode java = proposed.addObject();
        java.put("name", "Java");
        java.put("evidence", "");
        GeneratedApplicationDocuments documents = parseWithoutValidation(
                fixture.output());

        validator.validate(
                fixture.output(),
                documents,
                fixture.catalog(),
                true,
                true,
                true,
                false);

        assertEquals(8, projectedSkillClaims(documents).size());
        assertEquals(8, fixture.output().at("/cv/coreSkills").size());
        assertEquals(40, documents.getClaims().size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void fullIdentityClaimForcesIsolatedTitleAndNeverUsesReservedSibling()
            throws Exception {
        List<String> fullPaths = new ArrayList<>();
        fullPaths.add("/cv/targetRole");
        for (int index = 0; index < 29; index++) {
            fullPaths.add(
                    "/cv/workHistory/0/responsibilities/" + index);
        }
        GeneratedClaim identity = generatedClaim(
                "CLAIM-001",
                List.of("JOB.TITLE"),
                fullPaths);
        GeneratedClaim opening = generatedClaim(
                "CLAIM-9001",
                List.of("REQUEST.GENERATION_INTENT", "JOB.TITLE", "JOB.COMPANY"),
                List.of("/coverLetter/openingParagraph"));
        GeneratedClaim summary = generatedClaim(
                "CLAIM-9003",
                List.of("JOB.TITLE"),
                List.of("/cv/personalSummary"));
        Map<String, List<ApprovedEvidenceRecord>> evidenceById =
                new LinkedHashMap<>();
        evidenceById.put("JOB.TITLE", List.of(new ApprovedEvidenceRecord(
                "JOB.TITLE",
                EvidenceSource.JOB,
                "/job/title",
                "Java Developer")));
        Method method = ClaimEvidenceValidator.class.getDeclaredMethod(
                "ensureCanonicalTitleCoverage",
                List.class,
                Map.class,
                String.class,
                String.class,
                EvidencePurpose.class);
        method.setAccessible(true);

        List<GeneratedClaim> result = (List<GeneratedClaim>) method.invoke(
                validator,
                List.of(identity, opening, summary),
                evidenceById,
                "/cv/title",
                "/cv/targetRole",
                EvidencePurpose.CV);

        assertEquals(4, result.size());
        assertEquals(30, result.get(0).getContentPaths().size());
        GeneratedClaim title = result.stream()
                .filter(claim -> List.of("/cv/title")
                        .equals(claim.getContentPaths()))
                .findFirst()
                .orElseThrow();
        assertEquals(List.of("JOB.TITLE"), title.getEvidenceIds());
        assertFalse(title.getClaimId().startsWith("CLAIM-9"));
        assertTrue(result.stream()
                .filter(claim -> claim.getClaimId().startsWith("CLAIM-9"))
                .noneMatch(claim -> claim.getContentPaths()
                        .contains("/cv/title")));
    }

    private CapacityFixture capacityFixture() throws Exception {
        ObjectNode output = activeOutput();
        ClaimEvidenceCatalog base = withTwelveSkills(baseCatalog());
        List<ApprovedEvidenceRecord> records = new ArrayList<>(base.records());
        ArrayNode qualifications = (ArrayNode) output.at("/cv/qualifications");
        ArrayNode claims = (ArrayNode) output.path("claims");
        for (int index = 0; index < 20; index++) {
            String name = "Capacity Qualification " + (index + 1);
            String evidenceId =
                    "PROFILE.QUALIFICATION." + (index + 2) + ".NAME";
            ObjectNode qualification = qualifications.addObject();
            qualification.put("qualificationName", name);
            qualification.put("issuingBody", "");
            qualification.put("status", "");
            qualification.put("grade", "");
            qualification.put("dateAchieved", "");
            qualification.put("expectedCompletion", "");
            claims.add(claim(
                    "CLAIM-" + (100 + index),
                    List.of(evidenceId),
                    List.of("/cv/qualifications/" + index
                            + "/qualificationName")));
            records.add(new ApprovedEvidenceRecord(
                    evidenceId,
                    EvidenceSource.PROFILE,
                    "/profile/qualifications/" + (index + 1)
                            + "/qualificationName",
                    name));
        }

        ObjectNode history = ((ArrayNode) output.at("/cv/workHistory"))
                .addObject();
        history.put("jobTitle", "Capacity Engineer");
        history.put("employer", "Capacity Ltd");
        history.put("startDate", "2020-01");
        history.put("endDate", "");
        history.putArray("responsibilities")
                .add("Delivered capacity-specific service.");
        history.put("tailoredDescription", "");
        String employmentPrefix = "PROFILE.EMPLOYMENT.2.";
        addAtomicClaimAndEvidence(
                claims,
                records,
                "CLAIM-200",
                employmentPrefix + "JOB_TITLE",
                "/profile/employmentHistory/1/jobTitle",
                "Capacity Engineer",
                "/cv/workHistory/0/jobTitle");
        addAtomicClaimAndEvidence(
                claims,
                records,
                "CLAIM-201",
                employmentPrefix + "EMPLOYER",
                "/profile/employmentHistory/1/employer",
                "Capacity Ltd",
                "/cv/workHistory/0/employer");
        addAtomicClaimAndEvidence(
                claims,
                records,
                "CLAIM-202",
                employmentPrefix + "START_DATE",
                "/profile/employmentHistory/1/startDate",
                "2020-01",
                "/cv/workHistory/0/startDate");
        addAtomicClaimAndEvidence(
                claims,
                records,
                "CLAIM-203",
                employmentPrefix + "RESPONSIBILITIES",
                "/profile/employmentHistory/1/responsibilities",
                "Delivered capacity-specific service.",
                "/cv/workHistory/0/responsibilities/0");
        proposeTwelveSkills(output);
        assertEquals(29, claims.size());
        return new CapacityFixture(
                output,
                new ClaimEvidenceCatalog(
                        base.catalogVersion(),
                        List.copyOf(records),
                        base.sectionOrder()));
    }

    private void addProjectPathsOwnedByFormerIdentityClaims(
            ObjectNode output,
            GeneratedApplicationDocuments documents
    ) {
        ObjectNode project = ((ArrayNode) output.at("/cv/projects"))
                .addObject();
        project.put("title", "Capacity Project");
        project.put("role", "");
        project.put("context", "");
        project.put("startDate", "");
        project.put("endDate", "");
        project.put("description", "Capacity project description.");
        project.putArray("highlights");

        GeneratedClaim cvIdentity = claimFor(documents, "/cv/targetRole");
        cvIdentity.setEvidenceIds(List.of("PROFILE.PROJECT.1.HEADING"));
        cvIdentity.setContentPaths(List.of("/cv/projects/0/title"));
        GeneratedClaim coverIdentity = claimFor(
                documents,
                "/coverLetter/jobTitle");
        coverIdentity.setEvidenceIds(List.of(
                "PROFILE.PROJECT.1.DESCRIPTION"));
        coverIdentity.setContentPaths(List.of(
                "/cv/projects/0/description"));

        GeneratedClaim summary = documents.getClaims().stream()
                .filter(claim -> "CLAIM-9003".equals(claim.getClaimId()))
                .findFirst()
                .orElseThrow();
        summary.setContentPaths(List.of(
                "/cv/personalSummary",
                "/cv/targetRole"));
        GeneratedClaim opening = documents.getClaims().stream()
                .filter(claim -> "CLAIM-9001".equals(claim.getClaimId()))
                .findFirst()
                .orElseThrow();
        opening.setContentPaths(List.of(
                "/coverLetter/openingParagraph",
                "/coverLetter/jobTitle"));
    }

    private ClaimEvidenceCatalog withProjectEvidence(
            ClaimEvidenceCatalog catalog
    ) {
        List<ApprovedEvidenceRecord> records = new ArrayList<>(catalog.records());
        records.add(new ApprovedEvidenceRecord(
                "PROFILE.PROJECT.1.HEADING",
                EvidenceSource.PROFILE,
                "/profile/projects/0/title",
                "Capacity Project",
                "HEADING",
                "PROJECT",
                EvidencePurpose.CV));
        records.add(new ApprovedEvidenceRecord(
                "PROFILE.PROJECT.1.DESCRIPTION",
                EvidenceSource.PROFILE,
                "/profile/projects/0/description",
                "Capacity project description.",
                "DESCRIPTION",
                "PROJECT",
                EvidencePurpose.CV));
        return new ClaimEvidenceCatalog(
                catalog.catalogVersion(),
                List.copyOf(records),
                catalog.sectionOrder());
    }

    private void addAtomicClaimAndEvidence(
            ArrayNode claims,
            List<ApprovedEvidenceRecord> records,
            String claimId,
            String evidenceId,
            String sourcePath,
            String value,
            String contentPath
    ) {
        claims.add(claim(
                claimId,
                List.of(evidenceId),
                List.of(contentPath)));
        records.add(new ApprovedEvidenceRecord(
                evidenceId,
                EvidenceSource.PROFILE,
                sourcePath,
                value));
    }

    private ClaimEvidenceCatalog withTwelveSkills(
            ClaimEvidenceCatalog catalog
    ) {
        List<ApprovedEvidenceRecord> records = new ArrayList<>(catalog.records());
        for (int index = 3; index <= 12; index++) {
            records.add(new ApprovedEvidenceRecord(
                    "PROFILE.SKILL." + index,
                    EvidenceSource.PROFILE,
                    "/profile/skills/" + (index - 1),
                    "Capacity Skill " + index));
        }
        return new ClaimEvidenceCatalog(
                catalog.catalogVersion(),
                List.copyOf(records),
                catalog.sectionOrder());
    }

    private void proposeTwelveSkills(ObjectNode output) {
        ArrayNode skills = (ArrayNode) output.at("/cv/coreSkills");
        skills.removeAll();
        for (String value : List.of("Java", "Spring")) {
            ObjectNode skill = skills.addObject();
            skill.put("name", value);
            skill.put("evidence", "");
        }
        for (int index = 3; index <= 12; index++) {
            ObjectNode skill = skills.addObject();
            skill.put("name", "Capacity Skill " + index);
            skill.put("evidence", "");
        }
    }

    private ObjectNode activeOutput() throws Exception {
        return (ObjectNode) objectMapper.readTree(
                CvCoverLetterServiceTest.activeValidJson());
    }

    private GeneratedApplicationDocuments parseWithoutValidation(
            ObjectNode output
    ) throws Exception {
        return parser.parse(objectMapper.writeValueAsString(output), schema);
    }

    private ClaimEvidenceCatalog baseCatalog() {
        return new ClaimEvidenceCatalogFactory().create(
                new GenerationInputNormalizer().normalize(
                        "owner-123",
                        GenerationInputFixtures.validRequest()));
    }

    private ObjectNode workHistory(
            String tailoredDescription,
            boolean includeResponsibility
    ) {
        ObjectNode history = objectMapper.createObjectNode();
        history.put("jobTitle", "Software Engineer");
        history.put("employer", "Example Ltd");
        history.put("startDate", "2022-03");
        history.put("endDate", "");
        ArrayNode responsibilities = history.putArray("responsibilities");
        if (includeResponsibility) {
            responsibilities.add("Built and maintained Java services.");
        }
        history.put("tailoredDescription", tailoredDescription);
        return history;
    }

    private ObjectNode claim(
            String claimId,
            List<String> evidenceIds,
            List<String> contentPaths
    ) {
        ObjectNode claim = objectMapper.createObjectNode();
        claim.put("claimId", claimId);
        claim.put("disposition", "SUPPORTED");
        ArrayNode evidence = claim.putArray("evidenceIds");
        evidenceIds.forEach(evidence::add);
        ArrayNode paths = claim.putArray("contentPaths");
        contentPaths.forEach(paths::add);
        claim.put("reviewText", "");
        return claim;
    }

    private GeneratedClaim generatedClaim(
            String claimId,
            List<String> evidenceIds,
            List<String> contentPaths
    ) {
        GeneratedClaim claim = new GeneratedClaim();
        claim.setClaimId(claimId);
        claim.setDisposition(ClaimDisposition.SUPPORTED);
        claim.setEvidenceIds(evidenceIds);
        claim.setContentPaths(contentPaths);
        claim.setReviewText("");
        return claim;
    }

    private GeneratedClaim claimFor(
            GeneratedApplicationDocuments documents,
            String contentPath
    ) {
        return documents.getClaims().stream()
                .filter(claim -> claim.getContentPaths() != null
                        && claim.getContentPaths().contains(contentPath))
                .findFirst()
                .orElseThrow();
    }

    private long coverageCount(
            GeneratedApplicationDocuments documents,
            String contentPath
    ) {
        return documents.getClaims().stream()
                .filter(claim -> claim.getContentPaths() != null)
                .flatMap(claim -> claim.getContentPaths().stream())
                .filter(contentPath::equals)
                .count();
    }

    private List<GeneratedClaim> projectedSkillClaims(
            GeneratedApplicationDocuments documents
    ) {
        return documents.getClaims().stream()
                .filter(claim -> claim.getContentPaths() != null
                        && claim.getContentPaths().size() == 1
                        && claim.getContentPaths().get(0).matches(
                                "/cv/coreSkills/\\d+/name"))
                .toList();
    }

    private record CapacityFixture(
            ObjectNode output,
            ClaimEvidenceCatalog catalog) {
    }
}
