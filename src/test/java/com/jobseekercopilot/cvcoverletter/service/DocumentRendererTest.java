package com.jobseekercopilot.cvcoverletter.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.cvcoverletter.dto.ContactDetails;
import com.jobseekercopilot.cvcoverletter.dto.GeneratedApplicationDocuments;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentRendererTest {

    private GeneratedApplicationDocuments documents;

    @BeforeEach
    void setUp() throws Exception {
        documents = new ObjectMapper().readValue(
                """
                {
                  "cv": {
                    "title": "Tailored Developer CV",
                    "targetRole": "Developer",
                    "personalSummary": "A capable developer.",
                    "coreSkills": [{"name":"Java","evidence":"Built services"}],
                    "projects": [{"title":"Job Seeker Copilot","role":"Developer","context":"","startDate":"","endDate":"","description":"Built a useful service.","highlights":["Added safe generation"]}],
                    "qualifications": [{"qualificationName":"BSc Computing","issuingBody":"Example University","status":"Completed","grade":"First","dateAchieved":"2024","expectedCompletion":""}],
                    "workHistory": [{"jobTitle":"Engineer","employer":"Acme","startDate":"2022","endDate":"Present","responsibilities":["Built APIs"],"tailoredDescription":"Relevant delivery."}]
                  },
                  "coverLetter": {
                    "title": "Developer Cover Letter",
                    "jobTitle": "Developer",
                    "companyName": "Example Ltd",
                    "greeting": "Dear Hiring Manager",
                    "openingParagraph": "I am applying for the role.",
                    "bodyParagraphs": ["My experience is a strong match.", "I build useful services."],
                    "closingParagraph": "Thank you for your consideration.",
                    "signOff": "Yours sincerely"
                  },
                  "generationNotes": {"assumptionsMade":[],"missingInformation":[],"tailoringSummary":"Focused on Java."},
                  "claims": []
                }
                """,
                GeneratedApplicationDocuments.class);
    }

    @Test
    void cvRendererOutputsAllRequiredSections() {
        String result = new CvDocumentRenderer().render(documents.getCv(),
                new ContactDetails("Alex Candidate", "alex@example.com", "London, SW1A 1AA"));

        assertTrue(result.contains("Tailored Developer CV"));
        assertTrue(result.contains("Alex Candidate\nalex@example.com\nLondon, SW1A 1AA"));
        assertTrue(result.contains("Professional Profile\nA capable developer."));
        assertTrue(result.contains(
                "Projects\nJob Seeker Copilot - Developer\nBuilt a useful service."));
        assertTrue(result.contains("Technical Skills\nJava"));
        assertFalse(result.contains("Java: Built services"));
        assertTrue(result.contains("Employment History\nEngineer - Acme"));
        assertTrue(result.contains(
                "Education and Qualifications\n- BSc Computing"));
    }

    @Test
    void coverLetterRendererOutputsGreetingParagraphsAndSignOff() {
        String result = new CoverLetterDocumentRenderer().render(documents.getCoverLetter(),
                new ContactDetails("Alex Candidate", "alex@example.com", "London, SW1A 1AA"));

        assertTrue(result.contains("Alex Candidate\nalex@example.com\nLondon, SW1A 1AA"));
        assertTrue(result.contains(
                "Application for Developer at Example Ltd"));
        assertTrue(result.contains("Dear Hiring Manager,"));
        assertTrue(result.contains("I am applying for the role."));
        assertTrue(result.contains("My experience is a strong match."));
        assertTrue(result.endsWith("Yours faithfully,\nAlex Candidate"));
    }

    @Test
    void projectOnlyCvUsesTheGovernedVisibleOrderAndOmitsEmployment() {
        documents.getCv().setWorkHistory(java.util.List.of());

        String result = new CvDocumentRenderer().render(documents.getCv());

        int profile = result.indexOf("Technical Profile");
        int projects = result.indexOf("Projects");
        int skills = result.indexOf("Technical Skills");
        int education = result.indexOf("Education and Qualifications");
        assertTrue(profile >= 0);
        assertTrue(profile < projects);
        assertTrue(projects < skills);
        assertTrue(skills < education);
        assertFalse(result.contains("Employment History"));
    }

    @Test
    void cvRendererOmitsUnsupportedEmptySections() {
        documents.getCv().setCoreSkills(java.util.List.of());
        documents.getCv().setProjects(java.util.List.of());
        documents.getCv().setWorkHistory(java.util.List.of());
        documents.getCv().setQualifications(java.util.List.of());

        String result = new CvDocumentRenderer().render(documents.getCv());

        assertFalse(result.contains("Technical Skills"));
        assertFalse(result.contains("Projects"));
        assertFalse(result.contains("Employment History"));
        assertFalse(result.contains("Education and Qualifications"));
    }
}
