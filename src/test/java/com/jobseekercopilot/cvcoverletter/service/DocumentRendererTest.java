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
                    "projects": [{"title":"Job Seeker Copilot","role":"Developer","context":"","startDate":"2026-05-01","endDate":"Present","description":"Built a useful service.","highlights":["Added safe generation"]}],
                    "qualifications": [{"qualificationName":"BSc Computing","issuingBody":"Example University","status":"Completed","grade":"First","dateAchieved":"2024","expectedCompletion":""}],
                    "workHistory": [{"jobTitle":"Engineer","employer":"Acme","startDate":"2022-03","endDate":"Present","responsibilities":["Built APIs"],"tailoredDescription":"Relevant delivery."}]
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
                new ContactDetails(
                        "Alex Candidate",
                        "alex@example.com",
                        "London, SW1A 1AA",
                        "+44 20 7946 0958",
                        java.util.List.of(new ContactDetails.ProfessionalLink(
                                "GitHub",
                                "https://github.com/example"))));

        assertTrue(result.contains("Tailored Developer CV"));
        assertTrue(result.contains("Alex Candidate\nalex@example.com\nLondon, SW1A 1AA"));
        assertTrue(result.contains(
                "+44 20 7946 0958\nGitHub: https://github.com/example"));
        assertTrue(result.contains("Professional Profile\nA capable developer."));
        assertTrue(result.contains(
                "Selected Experience\nJob Seeker Copilot - Developer\nMay 2026 – Present\nBuilt a useful service."));
        assertTrue(result.contains("Key Skills\nJava"));
        assertFalse(result.contains("Java: Built services"));
        assertTrue(result.contains("Professional Experience\nEngineer - Acme\nMarch 2022 – Present"));
        assertTrue(result.contains(
                "Education and Qualifications\n- BSc Computing"));
        assertFalse(result.contains("Completed, First"));
    }

    @Test
    void cvRendererDoesNotRepeatProjectRoleAlreadyPresentInTitle() {
        documents.getCv().getProjects().get(0)
                .setTitle("Codecademy Docs — Open Source Contributor");
        documents.getCv().getProjects().get(0)
                .setRole("Open Source Contributor");

        String result = new CvDocumentRenderer().render(documents.getCv());

        assertTrue(result.contains(
                "Codecademy Docs — Open Source Contributor\n"));
        assertFalse(result.contains(
                "Open Source Contributor - Open Source Contributor"));
    }

    @Test
    void cvRendererCollapsesIdenticalDatesAndNormalisesAwardedGrades() {
        var project = documents.getCv().getProjects().get(0);
        project.setStartDate("2021-12");
        project.setEndDate("2021-12");
        var qualification = documents.getCv().getQualifications().get(0);
        qualification.setGrade(
                "Awarded a UK upper second-class honours degree (2:1).");

        String result = new CvDocumentRenderer().render(documents.getCv());

        assertTrue(result.contains("December 2021\n"));
        assertFalse(result.contains("December 2021 – December 2021"));
        assertTrue(result.contains("Example University, 2:1, 2024"));
        assertFalse(result.contains("Awarded a UK"));
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
        assertTrue(result.endsWith("Yours sincerely,\nAlex Candidate"));
    }

    @Test
    void projectOnlyCvUsesTheGovernedVisibleOrderAndOmitsEmployment() {
        documents.getCv().setWorkHistory(java.util.List.of());

        String result = new CvDocumentRenderer().render(documents.getCv());

        int profile = result.indexOf("Professional Profile");
        int skills = result.indexOf("Key Skills");
        int projects = result.indexOf("Selected Experience");
        int education = result.indexOf("Education and Qualifications");
        assertTrue(profile >= 0);
        assertTrue(profile < skills);
        assertTrue(skills < projects);
        assertTrue(skills < education);
        assertFalse(result.contains("Professional Experience"));
    }

    @Test
    void cvRendererOmitsUnsupportedEmptySections() {
        documents.getCv().setCoreSkills(java.util.List.of());
        documents.getCv().setProjects(java.util.List.of());
        documents.getCv().setWorkHistory(java.util.List.of());
        documents.getCv().setQualifications(java.util.List.of());

        String result = new CvDocumentRenderer().render(documents.getCv());

        assertFalse(result.contains("Key Skills"));
        assertFalse(result.contains("Projects"));
        assertFalse(result.contains("Professional Experience"));
        assertFalse(result.contains("Education and Qualifications"));
    }
}
