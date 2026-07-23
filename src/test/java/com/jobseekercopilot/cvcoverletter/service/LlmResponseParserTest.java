package com.jobseekercopilot.cvcoverletter.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.cvcoverletter.dto.GeneratedApplicationDocuments;
import com.jobseekercopilot.cvcoverletter.exception.InvalidLlmResponseException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmResponseParserTest {

    private final LlmResponseParser parser = new LlmResponseParser(new ObjectMapper());

    @Test
    void parsesValidJson() {
        GeneratedApplicationDocuments result = parser.parse(CvCoverLetterServiceTest.validJson());

        assertEquals("Tailored Developer CV", result.getCv().getTitle());
        assertEquals("Developer Cover Letter", result.getCoverLetter().getTitle());
        assertEquals("Focused on Java.", result.getGenerationNotes().getTailoringSummary());
    }

    @Test
    void rejectsInvalidJson() {
        InvalidLlmResponseException error = assertThrows(
                InvalidLlmResponseException.class, () -> parser.parse("not JSON"));
        assertTrue(error.getMessage().contains("not valid JSON"));
    }

    @Test
    void normalizesMissingListsToEmptyLists() {
        GeneratedApplicationDocuments result = parser.parse("""
                {
                  "cv": {
                    "title": "Tailored CV",
                    "targetRole": "Developer",
                    "personalSummary": "A capable developer.",
                    "coreSkills": null,
                    "qualifications": null,
                    "workHistory": null
                  },
                  "coverLetter": {
                    "title": "Cover Letter",
                    "jobTitle": "Developer",
                    "companyName": "Example Ltd",
                    "greeting": "Dear Hiring Manager",
                    "openingParagraph": "I am applying for the role.",
                    "bodyParagraphs": null,
                    "closingParagraph": "Thank you for your consideration.",
                    "signOff": "Yours faithfully"
                  },
                  "generationNotes": {
                    "assumptionsMade": null,
                    "missingInformation": null,
                    "tailoringSummary": "Focused on the advert."
                  }
                }
                """);

        assertTrue(result.getCv().getCoreSkills().isEmpty());
        assertTrue(result.getCv().getQualifications().isEmpty());
        assertTrue(result.getCv().getWorkHistory().isEmpty());
        assertTrue(result.getCoverLetter().getBodyParagraphs().isEmpty());
        assertTrue(result.getGenerationNotes().getAssumptionsMade().isEmpty());
        assertTrue(result.getGenerationNotes().getMissingInformation().isEmpty());
    }

    @Test
    void rejectsMissingRequiredFields() {
        InvalidLlmResponseException error = assertThrows(InvalidLlmResponseException.class,
                () -> parser.parse("{\"cv\":{},\"coverLetter\":{}}"));

        assertTrue(error.getMessage().contains("cv.title"));
        assertTrue(error.getMessage().contains("coverLetter.signOff"));
    }
}
