package com.jobseekercopilot.cvcoverletter.service;

import static com.jobseekercopilot.cvcoverletter.GenerationInputFixtures.validRequest;
import static com.jobseekercopilot.cvcoverletter.GenerationInputFixtures.validSelectedRequest;
import static com.jobseekercopilot.cvcoverletter.GenerationInputFixtures.validVersionedRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.cvcoverletter.config.LlmProperties;
import com.jobseekercopilot.cvcoverletter.config.PromptBundleProperties;
import com.jobseekercopilot.cvcoverletter.dto.EvidenceSnapshotFactInput;
import com.jobseekercopilot.cvcoverletter.dto.DraftOutputType;
import com.jobseekercopilot.cvcoverletter.dto.ProfessionalContactInputSnapshot;
import com.jobseekercopilot.cvcoverletter.dto.ProfessionalLinkInput;
import com.jobseekercopilot.cvcoverletter.model.CvCoverLetterPrompt;
import com.jobseekercopilot.cvcoverletter.model.EvidenceSource;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

class PromptBuilderServiceTest {

    @Test
    void activeQualityReleaseAddsDeterministicRankingOnlyToUntrustedInput() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        var input = new GenerationInputNormalizer(
                Clock.fixed(
                        Instant.parse("2026-08-11T12:00:00Z"),
                        ZoneOffset.UTC))
                .normalize("fictional-owner", validVersionedRequest());

        CvCoverLetterPrompt active = new PromptBuilderService(
                objectMapper,
                registry(objectMapper, "cv-cover-letter-1.7.0"),
                new LlmProperties(),
                new ClaimEvidenceCatalogFactory())
                .buildPrompt(input);
        CvCoverLetterPrompt rollback = new PromptBuilderService(
                objectMapper,
                registry(objectMapper, "cv-cover-letter-1.6.1"),
                new LlmProperties(),
                new ClaimEvidenceCatalogFactory())
                .buildPrompt(input);

        assertTrue(active.getUntrustedInput().contains("\"applicationQualityPlan\""));
        assertTrue(active.getUntrustedInput().contains("\"rankedEvidence\""));
        assertTrue(active.getUntrustedInput().contains(
                ApplicationQualityPlanner.VERSION));
        assertFalse(active.getUntrustedInput().contains("\"sourcePath\""));
        assertFalse(active.getTrustedInstructions().contains("\"rankedEvidence\""));
        assertFalse(rollback.getUntrustedInput().contains(
                "\"applicationQualityPlan\""));
    }

    @Test
    void replayCanRebuildAnApprovedHistoricalPromptReleaseExplicitly() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        PromptBuilderService service = new PromptBuilderService(
                objectMapper,
                registry(objectMapper, "cv-cover-letter-1.5.11"),
                new LlmProperties(),
                new ClaimEvidenceCatalogFactory());
        var input = new GenerationInputNormalizer(
                Clock.fixed(
                        Instant.parse("2026-07-24T13:00:00Z"),
                        ZoneOffset.UTC))
                .normalize("owner-secret-123", validRequest());

        CvCoverLetterPrompt prompt = service.buildPrompt(
                input,
                "cv-cover-letter-1.5.6");

        assertEquals(
                "cv-cover-letter-1.5.6",
                prompt.getGenerationMetadata().releaseId());
        assertEquals("3.7.0", prompt.getGenerationMetadata().schemaVersion());
    }

    @Test
    void acceptsNinetyEightFactsAtExpandedUntrustedBoundary() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        var request = validVersionedRequest();
        List.of(
                        request.getEvidenceSnapshots().getCv(),
                        request.getEvidenceSnapshots().getCoverLetter())
                .forEach(snapshot -> {
                    var selection = snapshot.getSelections().get(0);
                    var facts = new ArrayList<EvidenceSnapshotFactInput>();
                    for (int index = 0; index < 49; index++) {
                        UUID factId = UUID.nameUUIDFromBytes((
                                snapshot.getPurpose().name() + ":" + index)
                                .getBytes(StandardCharsets.UTF_8));
                        facts.add(new EvidenceSnapshotFactInput(
                                factId,
                                "DESCRIPTION",
                                "Evidence " + index + " " + "x".repeat(100),
                                false));
                    }
                    selection.setFacts(facts);
                });

        CvCoverLetterPrompt prompt = new PromptBuilderService(
                objectMapper,
                registry(objectMapper, "cv-cover-letter-1.5.11"),
                new LlmProperties(),
                new ClaimEvidenceCatalogFactory())
                .buildPrompt(new GenerationInputNormalizer(
                        Clock.fixed(
                                Instant.parse("2026-07-24T13:00:00Z"),
                                ZoneOffset.UTC))
                        .normalize("owner-secret-123", request));

        assertTrue(prompt.getUntrustedInput().length() > 40_000);
        assertTrue(prompt.getUntrustedInput().length()
                <= PromptBuilderService.MAX_UNTRUSTED_INPUT_CHARACTERS);
    }

    @Test
    void onlyTheCanonicalSkillsReleasePublishesRevisionDeclaredSkills() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        GenerationInputNormalizer normalizer = new GenerationInputNormalizer(
                Clock.fixed(
                        Instant.parse("2026-07-24T13:00:00Z"),
                        ZoneOffset.UTC));
        var request = validVersionedRequest();
        request.getProfile().setSkills(java.util.List.of("Spring"));
        var input = normalizer.normalize("owner-secret-123", request);

        CvCoverLetterPrompt active = new PromptBuilderService(
                objectMapper,
                registry(objectMapper, "cv-cover-letter-1.5.11"),
                new LlmProperties(),
                new ClaimEvidenceCatalogFactory())
                .buildPrompt(input);
        CvCoverLetterPrompt rollback = new PromptBuilderService(
                objectMapper,
                registry(objectMapper, "cv-cover-letter-1.5.6"),
                new LlmProperties(),
                new ClaimEvidenceCatalogFactory())
                .buildPrompt(input);

        assertTrue(active.getEvidenceCatalog().records().stream()
                .anyMatch(record -> record.source()
                        == EvidenceSource.PROFILE_REVISION));
        String declaredEvidenceId = active.getEvidenceCatalog().records().stream()
                .filter(record -> record.source()
                        == EvidenceSource.PROFILE_REVISION)
                .map(record -> record.evidenceId())
                .findFirst()
                .orElseThrow();
        assertFalse(active.getUntrustedInput().contains(declaredEvidenceId));
        assertFalse(active.getOutputSchema().at("/$defs/approvedEvidenceId/enum")
                .toString().contains("\"" + declaredEvidenceId + "\""));
        assertFalse(active.getUntrustedInput().contains("PROFILE_REVISION"));
        assertTrue(active.getUntrustedInput().contains(
                "serviceProjectedCoreSkillCandidates"));
        assertTrue(active.getUntrustedInput().contains("Spring"));
        assertFalse(rollback.getEvidenceCatalog().records().stream()
                .anyMatch(record -> record.source()
                        == EvidenceSource.PROFILE_REVISION));
        assertFalse(rollback.getUntrustedInput().contains(
                "serviceProjectedCoreSkillCandidates"));
    }

    @Test
    void separatesReviewedInstructionsUntrustedEvidenceAndOutputSchema() throws Exception {
        LlmProperties properties = new LlmProperties();
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        PromptBuilderService service = new PromptBuilderService(
                objectMapper,
                registry(objectMapper, "cv-cover-letter-1.5.11"),
                properties,
                new ClaimEvidenceCatalogFactory());
        GenerationInputNormalizer normalizer = new GenerationInputNormalizer(
                Clock.fixed(Instant.parse("2026-07-24T13:00:00Z"), ZoneOffset.UTC));

        var request = validRequest();
        request.getProfile().setProfessionalContact(
                new ProfessionalContactInputSnapshot(
                        "+44 7517 777951",
                        List.of(new ProfessionalLinkInput(
                                "GitHub",
                                "https://github.com/jobseekercopilot"))));
        CvCoverLetterPrompt result = service.buildPrompt(
                normalizer.normalize("owner-secret-123", request));

        assertTrue(result.getTrustedInstructions().contains("TRUTHFULNESS RULES"));
        assertTrue(result.getTrustedInstructions().contains("UK English"));
        assertTrue(result.getTrustedInstructions().contains(
                "Use 3 to 24 concise bodyParagraphs"));
        assertTrue(result.getTrustedInstructions().contains(
                "inventory selections by document purpose"));
        assertTrue(result.getTrustedInstructions().contains("specific to the job"));
        assertTrue(result.getTrustedInstructions().contains("bundle=cv-cover-letter@1.5.11"));
        assertTrue(result.getTrustedInstructions().contains(
                "Generic, professional and application prose is"));
        assertTrue(result.getTrustedInstructions().contains(
                "cover-letter body paragraphs use"));
        assertTrue(result.getTrustedInstructions().contains(
                "ordinary claims[].contentPaths, every inline narrative"));
        assertTrue(result.getTrustedInstructions().contains(
                "The claims ledger is final-content provenance only."));
        assertTrue(result.getTrustedInstructions().contains(
                "Do not emit CONFIRMATION_REQUIRED or REJECTED ledger entries"));
        assertTrue(result.getTrustedInstructions().contains("UNTRUSTED CONTENT RULES"));
        assertTrue(result.getTrustedInstructions().contains(
                "[CANONICAL JOB FACTS SUPPLIED THROUGH THE UNTRUSTED INPUT CHANNEL]"));
        assertTrue(result.getTrustedInstructions().contains(
                "[STRICT JSON SCHEMA SUPPLIED THROUGH THE OUTPUT CONTRACT]"));
        assertTrue(result.getUntrustedInput().contains("UNTRUSTED_DATA_ONLY"));
        assertTrue(result.getUntrustedInput().contains("Build useful and reliable services."));
        assertTrue(result.getUntrustedInput().contains("Built and maintained Java services."));
        assertTrue(result.getUntrustedInput().contains("\"evidenceId\""));
        assertTrue(result.getUntrustedInput().contains("PROFILE.SKILL.1"));
        assertTrue(result.getOutputSchema().has("properties"));
        assertTrue(result.getOutputSchema().toString().contains("\"coverLetter\""));
        assertTrue(result.getOutputSchema().path("additionalProperties").isBoolean());
        assertTrue(result.getOutputSchema().at("/properties/cv/properties/title/pattern").isTextual());
        assertTrue(result.getOutputSchema().at(
                "/properties/coverLetter/properties/bodyParagraphs/maxItems").asInt() == 24);
        assertTrue(result.getOutputSchema().at(
                "/properties/cv/properties/coreSkills/maxItems").asInt() == 12);
        assertTrue(result.getOutputSchema().at(
                "/properties/cv/properties/coreSkills/items/properties/evidence/enum/0")
                .asText().isEmpty());
        assertTrue(result.getOutputSchema().at(
                "/properties/cv/properties/projects").isObject());
        assertTrue(result.getGenerationMetadata().bundleSha256().matches("[a-f0-9]{64}"));
        assertTrue(result.getOutputSchema().at(
                "/properties/claims/items/properties/disposition/enum").toString()
                .equals("[\"SUPPORTED\",\"REWORDED\"]"));
        assertTrue(result.getOutputSchema().at(
                "/properties/claims/items/properties/evidenceIds/minItems").asInt() == 1);
        assertEquals(
                "#/$defs/approvedEvidenceId",
                result.getOutputSchema().at(
                        "/properties/claims/items/properties/evidenceIds/items/$ref")
                        .asText());
        assertEquals(
                "#/$defs/approvedEvidenceId",
                result.getOutputSchema().at(
                        "/properties/personalSummaryClaim/properties/evidenceIds/items/$ref")
                        .asText());
        for (String inlineEvidencePath : List.of(
                "/properties/cv/properties/projects/items/properties/highlights/items/properties/evidenceIds/items/$ref",
                "/properties/cv/properties/workHistory/items/properties/responsibilities/items/properties/evidenceIds/items/$ref",
                "/properties/coverLetter/properties/bodyParagraphs/items/properties/evidenceIds/items/$ref")) {
            assertEquals(
                    "#/$defs/approvedEvidenceId",
                    result.getOutputSchema().at(inlineEvidencePath).asText(),
                    inlineEvidencePath);
        }
        Set<String> suppliedEvidenceIds = new LinkedHashSet<>();
        objectMapper.readTree(result.getUntrustedInput())
                .at("/approvedEvidence/records")
                .forEach(record -> suppliedEvidenceIds.add(
                        record.path("evidenceId").asText()));
        Set<String> schemaEvidenceIds = new LinkedHashSet<>();
        result.getOutputSchema().at("/$defs/approvedEvidenceId/enum")
                .forEach(value -> schemaEvidenceIds.add(value.asText()));
        assertEquals(suppliedEvidenceIds, schemaEvidenceIds);
        assertEquals(
                "3.6.13",
                new LlmResponseParser(
                        objectMapper,
                        new ClaimEvidenceValidator(),
                        new GeneratedDocumentQualityValidator())
                        .parserVersion(result.getOutputSchema()));
        assertTrue(result.getOutputSchema().toString().length()
                <= PromptBuilderService.MAX_OUTPUT_SCHEMA_CHARACTERS);
        assertTrue(result.getOutputSchema().at(
                "/properties/claims/items/properties/contentPaths/minItems").asInt() == 1);
        assertTrue(result.getOutputSchema().at(
                "/properties/claims/items/properties/reviewText/enum/0").asText().isEmpty());
        assertTrue(result.getOutputSchema().at(
                "/properties/coverLetter/properties/openingParagraph/enum/0")
                .asText()
                .equals("Please consider my application for this role."));
        assertTrue(result.getOutputSchema().at(
                "/properties/coverLetter/properties/closingParagraph/enum/0")
                .asText()
                .equals("Thank you for considering my application."));
        assertTrue(result.getOutputSchema().at(
                "/properties/canonicalApplicationClaims/properties/opening/properties/claimId/enum/0")
                .asText()
                .equals("CLAIM-9001"));
        assertTrue(result.getOutputSchema().at(
                "/properties/canonicalApplicationClaims/properties/closing/properties/claimId/enum/0")
                .asText()
                .equals("CLAIM-9002"));
        assertTrue(result.getOutputSchema().at(
                "/properties/canonicalApplicationClaims/properties/opening/properties/generationIntentEvidenceId/enum/0")
                .asText()
                .equals("REQUEST.GENERATION_INTENT"));
        assertTrue(result.getOutputSchema().at(
                "/properties/personalSummaryClaim/properties/claimId/enum/0")
                .asText()
                .equals("CLAIM-9003"));
        assertTrue(result.getOutputSchema().at(
                "/properties/personalSummaryClaim/properties/contentPath/enum/0")
                .asText()
                .equals("/cv/personalSummary"));
        int ordinaryClaimLimit = result.getOutputSchema().at(
                "/properties/claims/maxItems").asInt();
        int canonicalClaimCount = result.getOutputSchema().at(
                "/properties/canonicalApplicationClaims/properties").size();
        int personalSummaryClaimCount = 1;
        int reservedSkillClaimCapacity = 8;
        int reservedInlineNarrativeCapacity = 9;
        assertTrue(ordinaryClaimLimit == 20);
        assertTrue(ordinaryClaimLimit
                + personalSummaryClaimCount
                + canonicalClaimCount
                + reservedSkillClaimCapacity
                + reservedInlineNarrativeCapacity == 40);
        String ordinaryPathPattern = result.getOutputSchema().at(
                "/properties/claims/items/properties/contentPaths/items/pattern").asText();
        assertFalse("/coverLetter/openingParagraph".matches(ordinaryPathPattern));
        assertFalse("/coverLetter/closingParagraph".matches(ordinaryPathPattern));
        assertFalse("/cv/title".matches(ordinaryPathPattern));
        assertFalse("/cv/personalSummary".matches(ordinaryPathPattern));
        assertFalse("/coverLetter/title".matches(ordinaryPathPattern));
        assertFalse("/cv/coreSkills/0/name".matches(ordinaryPathPattern));
        assertFalse("/cv/coreSkills/0/evidence".matches(ordinaryPathPattern));
        assertFalse("/cv/projects/0/highlights/0".matches(ordinaryPathPattern));
        assertFalse("/cv/workHistory/0/responsibilities/0"
                .matches(ordinaryPathPattern));
        assertFalse("/coverLetter/bodyParagraphs/0"
                .matches(ordinaryPathPattern));
        assertFalse("/cv/qualifications/0/qualificationTitle"
                .matches(ordinaryPathPattern));
        assertTrue("/cv/qualifications/0/qualificationName"
                .matches(ordinaryPathPattern));
        assertFalse(result.getOutputSchema().toString().contains("CONFIRMATION_REQUIRED"));
        assertFalse(result.getOutputSchema().toString().contains("REJECTED"));
        assertTrue(result.getOutputSchema().toString().contains("PROFILE.SKILL.1"));
        assertFalse(result.getOutputSchema().toString().contains("Build useful and reliable services."));
        assertTrue(result.getGenerationMetadata().schemaVersion().equals("3.10.0"));
        assertTrue(result.getEvidenceCatalog().records().stream()
                .anyMatch(record -> record.evidenceId().equals("JOB.TITLE")));
        assertFalse(result.getTrustedInstructions().contains("Build useful and reliable services."));
        assertFalse(result.getTrustedInstructions().contains("Built and maintained Java services."));
        assertFalse(result.getTrustedInstructions().contains("\"coverLetter\""));
        assertFalse(result.getUntrustedInput().contains("TRUTHFULNESS RULES"));
        assertFalse(result.getUntrustedInput().contains("\"coverLetter\""));
        assertFalse(result.getUntrustedInput().contains("owner-secret-123"));
        assertFalse(result.getUntrustedInput().contains("profile-123"));
        assertFalse(result.getUntrustedInput().contains("profile-v7"));
        assertFalse(result.getUntrustedInput().contains("job-456"));
        assertFalse(result.getUntrustedInput().contains("job-v12"));
        assertFalse(result.getUntrustedInput().contains("Alex Candidate"));
        assertFalse(result.getUntrustedInput().contains("alex@example.com"));
        assertFalse(result.getUntrustedInput().contains("+44 7517 777951"));
        assertFalse(result.getUntrustedInput().contains(
                "https://github.com/jobseekercopilot"));
        assertFalse(result.getUntrustedInput().contains("GitHub"));
        assertFalse(request.toString().contains("+44 7517 777951"));
        assertFalse(request.toString().contains(
                "https://github.com/jobseekercopilot"));
        assertFalse(result.toString().contains("Build useful and reliable services."));
        assertFalse(result.toString().contains("TRUTHFULNESS RULES"));
    }

    @Test
    void cvSelectionPublishesOnlyTheCvContractAndEvidence() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        var input = new GenerationInputNormalizer(
                Clock.fixed(
                        Instant.parse("2026-07-24T13:00:00Z"),
                        ZoneOffset.UTC))
                .normalizeSelected(
                        "owner-123",
                        DraftOutputType.CV,
                        validSelectedRequest(DraftOutputType.CV));
        CvCoverLetterPrompt prompt = new PromptBuilderService(
                objectMapper,
                registry(objectMapper, "cv-cover-letter-1.6.0"),
                new LlmProperties(),
                new ClaimEvidenceCatalogFactory())
                .buildPrompt(input, DraftOutputType.CV);

        assertTrue(prompt.getTrustedInstructions().contains(
                "Generate only the requested CV"));
        assertTrue(prompt.getOutputSchema().at("/properties/cv").isObject());
        assertFalse(prompt.getOutputSchema().at("/properties/coverLetter").isObject());
        assertTrue(prompt.getOutputSchema().at(
                "/properties/personalSummaryClaim").isObject());
        assertFalse(prompt.getOutputSchema().at(
                "/properties/canonicalApplicationClaims").isObject());
        assertEquals(
                "cv-cover-letter-output-cv",
                prompt.getGenerationMetadata().schemaId());
        assertTrue(prompt.getEvidenceCatalog().records().stream()
                .noneMatch(record -> record.purpose()
                        == com.jobseekercopilot.cvcoverletter.model.EvidencePurpose.COVER_LETTER));
        assertEquals(
                "3.6.13",
                parser(objectMapper).parserVersion(prompt.getOutputSchema()));
    }

    @Test
    void coverLetterSelectionPublishesOnlyTheCoverLetterContractAndEvidence() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        var input = new GenerationInputNormalizer(
                Clock.fixed(
                        Instant.parse("2026-07-24T13:00:00Z"),
                        ZoneOffset.UTC))
                .normalizeSelected(
                        "owner-123",
                        DraftOutputType.COVER_LETTER,
                        validSelectedRequest(DraftOutputType.COVER_LETTER));
        CvCoverLetterPrompt prompt = new PromptBuilderService(
                objectMapper,
                registry(objectMapper, "cv-cover-letter-1.6.0"),
                new LlmProperties(),
                new ClaimEvidenceCatalogFactory())
                .buildPrompt(input, DraftOutputType.COVER_LETTER);

        assertTrue(prompt.getTrustedInstructions().contains(
                "Generate only the requested cover letter"));
        assertFalse(prompt.getOutputSchema().at("/properties/cv").isObject());
        assertTrue(prompt.getOutputSchema().at(
                "/properties/coverLetter").isObject());
        assertFalse(prompt.getOutputSchema().at(
                "/properties/personalSummaryClaim").isObject());
        assertTrue(prompt.getOutputSchema().at(
                "/properties/canonicalApplicationClaims").isObject());
        assertEquals(
                "cv-cover-letter-output-cover_letter",
                prompt.getGenerationMetadata().schemaId());
        assertTrue(prompt.getEvidenceCatalog().records().stream()
                .noneMatch(record -> record.purpose()
                        == com.jobseekercopilot.cvcoverletter.model.EvidencePurpose.CV));
        assertEquals(
                "3.6.13",
                parser(objectMapper).parserVersion(prompt.getOutputSchema()));
    }

    private LlmResponseParser parser(ObjectMapper objectMapper) {
        return new LlmResponseParser(
                objectMapper,
                new ClaimEvidenceValidator(),
                new GeneratedDocumentQualityValidator());
    }

    private PromptBundleRegistry registry(ObjectMapper objectMapper, String releaseId) {
        PromptBundleProperties properties = new PromptBundleProperties();
        properties.setSelectedReleaseId(releaseId);
        PromptBundleRegistry registry =
                new PromptBundleRegistry(objectMapper, new DefaultResourceLoader(), properties);
        registry.initialize();
        return registry;
    }
}
