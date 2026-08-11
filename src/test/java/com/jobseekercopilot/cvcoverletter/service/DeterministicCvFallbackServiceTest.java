package com.jobseekercopilot.cvcoverletter.service;

import static com.jobseekercopilot.cvcoverletter.GenerationInputFixtures.validSelectedRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.cvcoverletter.dto.DraftOutputType;
import com.jobseekercopilot.cvcoverletter.dto.EvidenceCategory;
import com.jobseekercopilot.cvcoverletter.dto.EvidenceSnapshotFactInput;
import com.jobseekercopilot.cvcoverletter.dto.EvidenceSnapshotSelectionInput;
import com.jobseekercopilot.cvcoverletter.dto.SelectedDraftGenerationRequest;
import com.jobseekercopilot.cvcoverletter.model.NormalizedGenerationInput;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeterministicCvFallbackServiceTest {

    private final ObjectMapper objectMapper =
            new ObjectMapper().findAndRegisterModules();
    private final GenerationInputNormalizer normalizer =
            new GenerationInputNormalizer();
    private final ClaimEvidenceCatalogFactory catalogFactory =
            new ClaimEvidenceCatalogFactory();
    private final DeterministicCvFallbackService fallback =
            new DeterministicCvFallbackService(objectMapper);

    @Test
    void richProfileRemainsFactualChronologicalAndChangesWithTheVacancy()
            throws Exception {
        JsonNode backend = generate(
                "Build Java and Spring Boot REST APIs, integrate partner systems, "
                        + "and own reliable backend services in production.");
        JsonNode frontend = generate(
                "Build accessible Angular and TypeScript customer journeys, "
                        + "collaborate with product, and improve frontend usability.");

        String backendSummary = backend.at("/cv/personalSummary").asText();
        String frontendSummary = frontend.at("/cv/personalSummary").asText();
        assertNotEquals(backendSummary, frontendSummary);
        assertFalse(backendSummary.contains("verified profile skills"));
        assertTrue(backendSummary.contains("Java"));
        assertTrue(backendSummary.contains("Spring Boot"));
        assertTrue(frontendSummary.contains("Angular"));
        assertTrue(frontendSummary.contains("TypeScript"));

        assertEquals(
                List.of(
                        "Founder & Full-Stack Software Developer",
                        "Fresh Produce Team Member",
                        "Full-Stack Software Developer"),
                roles(backend));
        assertEquals(1, backend.at("/cv/workHistory/1/responsibilities").size());
        assertTrue(backend.toString().contains(
                "Maintained fresh produce availability and assisted customers."));
        assertFalse(backend.toString().contains("Kubernetes"));
        assertFalse(backend.toString().contains("without claiming"));

        assertTrue(List.of("Java", "Spring Boot", "REST APIs").contains(
                backend.at("/cv/coreSkills/0/name").asText()));
        assertTrue(List.of("Angular", "TypeScript", "Accessibility").contains(
                frontend.at("/cv/coreSkills/0/name").asText()));
        assertNotEquals(
                backend.at("/cv/workHistory/0/responsibilities/0/text").asText(),
                frontend.at("/cv/workHistory/0/responsibilities/0/text").asText());
    }

    private JsonNode generate(String description) throws Exception {
        SelectedDraftGenerationRequest request =
                validSelectedRequest(DraftOutputType.CV);
        request.getJob().setDescription(description);
        request.getProfile().setEmploymentHistory(new ArrayList<>());
        request.getProfile().setQualifications(new ArrayList<>());
        List<String> skills = new ArrayList<>(List.of(
                "Java", "Spring Boot", "REST APIs", "Angular", "TypeScript",
                "Accessibility", "Customer Service", "Git", "Docker"));
        for (int index = skills.size() + 1; index <= 40; index++) {
            skills.add("Verified Skill " + index);
        }
        request.getProfile().setSkills(skills);
        request.getEvidenceSnapshot().setSectionOrder(List.of(
                EvidenceCategory.EMPLOYMENT));
        request.getEvidenceSnapshot().setSelections(new ArrayList<>(List.of(
                employment(
                        "Full-Stack Software Developer",
                        "Fictional Rail Systems Studio",
                        "January 2021",
                        "August 2024",
                        "Developed Java and Spring Boot microservices for railway software.",
                        "Built Angular and TypeScript user interfaces with engineers."),
                employment(
                        "Founder & Full-Stack Software Developer",
                        "Fictional Career Platform",
                        "May 2026",
                        null,
                        "Designed Java and Spring Boot REST APIs and provider integrations.",
                        "Built Angular and TypeScript customer-facing job-search journeys.",
                        "Owned architecture, automated tests and production preparation without claiming unverified scale."),
                employment(
                        "Fresh Produce Team Member",
                        "Fictional Grocer",
                        "December 2025",
                        "May 2026",
                        "Maintained fresh produce availability and assisted customers.",
                        "Worked reliably within operational routines."))));

        NormalizedGenerationInput input = normalizer.normalizeSelected(
                "fictional-owner", DraftOutputType.CV, request);
        return objectMapper.readTree(fallback.build(
                input,
                catalogFactory.create(input, DraftOutputType.CV)));
    }

    private EvidenceSnapshotSelectionInput employment(
            String role,
            String employer,
            String start,
            String end,
            String... narratives) {
        List<EvidenceSnapshotFactInput> facts = new ArrayList<>();
        facts.add(fact("ROLE_TITLE", role));
        facts.add(fact("ORGANISATION", employer));
        facts.add(fact("START_DATE", start));
        if (end != null) {
            facts.add(fact("END_DATE", end));
        }
        for (String narrative : narratives) {
            facts.add(fact("RESPONSIBILITIES", narrative));
        }
        return new EvidenceSnapshotSelectionInput(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                EvidenceCategory.EMPLOYMENT,
                "e".repeat(64),
                facts);
    }

    private EvidenceSnapshotFactInput fact(String type, String value) {
        return new EvidenceSnapshotFactInput(
                UUID.randomUUID(), type, value, false);
    }

    private List<String> roles(JsonNode output) {
        List<String> roles = new ArrayList<>();
        output.at("/cv/workHistory").forEach(item ->
                roles.add(item.path("jobTitle").asText()));
        return roles;
    }
}
