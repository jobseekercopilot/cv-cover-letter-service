package com.jobseekercopilot.cvcoverletter.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.cvcoverletter.dto.GeneratedApplicationDocuments;
import com.jobseekercopilot.cvcoverletter.dto.GeneratedCoverLetter;
import com.jobseekercopilot.cvcoverletter.dto.GeneratedCv;
import com.jobseekercopilot.cvcoverletter.exception.InvalidLlmResponseException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class LlmResponseParser {

    private final ObjectMapper objectMapper;

    public GeneratedApplicationDocuments parse(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            throw new InvalidLlmResponseException("LLM gateway returned an empty response");
        }

        try {
            GeneratedApplicationDocuments documents = objectMapper.readValue(
                    stripMarkdownFence(rawResponse), GeneratedApplicationDocuments.class);
            normalizeLists(documents);
            validate(documents);
            return documents;
        } catch (JsonProcessingException exception) {
            throw new InvalidLlmResponseException("LLM response was not valid JSON", exception);
        }
    }

    private String stripMarkdownFence(String value) {
        String trimmed = value.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstLineEnd = trimmed.indexOf('\n');
        int closingFence = trimmed.lastIndexOf("```");
        return firstLineEnd >= 0 && closingFence > firstLineEnd
                ? trimmed.substring(firstLineEnd + 1, closingFence).trim()
                : trimmed;
    }

    private void validate(GeneratedApplicationDocuments documents) {
        List<String> missing = new ArrayList<>();
        if (documents == null || documents.getCv() == null) {
            missing.add("cv");
        } else {
            GeneratedCv cv = documents.getCv();
            requireText(cv.getTitle(), "cv.title", missing);
            requireText(cv.getPersonalSummary(), "cv.personalSummary", missing);
            requireList(cv.getCoreSkills(), "cv.coreSkills", missing);
            requireList(cv.getQualifications(), "cv.qualifications", missing);
            requireList(cv.getWorkHistory(), "cv.workHistory", missing);
        }
        if (documents == null || documents.getCoverLetter() == null) {
            missing.add("coverLetter");
        } else {
            GeneratedCoverLetter letter = documents.getCoverLetter();
            requireText(letter.getTitle(), "coverLetter.title", missing);
            requireText(letter.getGreeting(), "coverLetter.greeting", missing);
            requireText(letter.getOpeningParagraph(), "coverLetter.openingParagraph", missing);
            requireList(letter.getBodyParagraphs(), "coverLetter.bodyParagraphs", missing);
            requireText(letter.getClosingParagraph(), "coverLetter.closingParagraph", missing);
            requireText(letter.getSignOff(), "coverLetter.signOff", missing);
        }
        if (!missing.isEmpty()) {
            throw new InvalidLlmResponseException(
                    "LLM response is missing required fields: " + String.join(", ", missing));
        }
    }

    private void normalizeLists(GeneratedApplicationDocuments documents) {
        if (documents == null) {
            return;
        }
        if (documents.getCv() != null) {
            if (documents.getCv().getCoreSkills() == null) {
                documents.getCv().setCoreSkills(List.of());
            }
            if (documents.getCv().getQualifications() == null) {
                documents.getCv().setQualifications(List.of());
            }
            if (documents.getCv().getWorkHistory() == null) {
                documents.getCv().setWorkHistory(List.of());
            }
        }
        if (documents.getCoverLetter() != null && documents.getCoverLetter().getBodyParagraphs() == null) {
            documents.getCoverLetter().setBodyParagraphs(List.of());
        }
        if (documents.getGenerationNotes() != null) {
            if (documents.getGenerationNotes().getAssumptionsMade() == null) {
                documents.getGenerationNotes().setAssumptionsMade(List.of());
            }
            if (documents.getGenerationNotes().getMissingInformation() == null) {
                documents.getGenerationNotes().setMissingInformation(List.of());
            }
        }
    }

    private void requireText(String value, String name, List<String> missing) {
        if (value == null || value.isBlank()) {
            missing.add(name);
        }
    }

    private void requireList(List<?> value, String name, List<String> missing) {
        if (value == null) {
            missing.add(name);
        }
    }
}
