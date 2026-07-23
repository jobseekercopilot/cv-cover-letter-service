package com.jobseekercopilot.cvcoverletter.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.cvcoverletter.config.LlmProperties;
import com.jobseekercopilot.cvcoverletter.dto.GenerateCvCoverLetterResponse;
import com.jobseekercopilot.cvcoverletter.dto.GenerateRequest;
import com.jobseekercopilot.cvcoverletter.dto.Job;
import com.jobseekercopilot.cvcoverletter.dto.UserProfile;
import com.jobseekercopilot.cvcoverletter.exception.DownstreamServiceException;
import com.jobseekercopilot.cvcoverletter.exception.InvalidLlmResponseException;
import com.jobseekercopilot.cvcoverletter.model.CvCoverLetterPrompt;
import com.jobseekercopilot.generated.applicationtrackerservice.api.ApplicationRecordsApi;
import com.jobseekercopilot.generated.applicationtrackerservice.model.ApplicationRecordResponse;
import com.jobseekercopilot.generated.applicationtrackerservice.model.CreateApplicationRequest;
import com.jobseekercopilot.generated.documentstoreservice.api.GeneratedDocumentsApi;
import com.jobseekercopilot.generated.documentstoreservice.model.GeneratedDocumentResponse;
import com.jobseekercopilot.generated.llmgateway.api.LlmGenerationApi;
import com.jobseekercopilot.generated.llmgateway.model.GenerateResponse;
import com.jobseekercopilot.generated.llmgateway.model.LlmUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CvCoverLetterServiceTest {

    @Mock private PromptBuilderService promptBuilderService;
    @Mock private LlmGenerationApi llmGatewayApi;
    @Mock private PaymentBillingClient paymentBillingClient;
    @Mock private GeneratedDocumentsApi documentStoreApi;
    @Mock private ApplicationRecordsApi applicationTrackerApi;

    private CvCoverLetterService service;
    private GenerateRequest request;

    @BeforeEach
    void setUp() {
        LlmProperties properties = new LlmProperties();
        properties.setTaskType("CV_COVER_LETTER_GENERATION");
        properties.setTemperature(0.25);
        properties.setMaxTokens(2500);
        service = new CvCoverLetterService(promptBuilderService, llmGatewayApi, paymentBillingClient, properties,
                new LlmResponseParser(new ObjectMapper()), new CvDocumentRenderer(),
                new CoverLetterDocumentRenderer(), documentStoreApi, applicationTrackerApi);

        UserProfile profile = new UserProfile();
        profile.setUserId("user-123");
        Job job = new Job();
        job.setId("job-456");
        job.setTitle("Developer");
        job.setCompany("Example Ltd");
        job.setDescription("Build services");
        request = new GenerateRequest(profile, job);
        when(promptBuilderService.buildPrompt(profile, job))
                .thenReturn(CvCoverLetterPrompt.builder().finalPrompt("assembled prompt").build());
        when(paymentBillingClient.reserve(any(), any())).thenReturn(
                new PaymentBillingClient.ReservationResponse(UUID.randomUUID(), "user-123", 5000, 40000, "RESERVED"));
    }

    @Test
    void generatesStoresBothDocumentsCreatesApplicationAndReturnsContent() {
        when(llmGatewayApi.generate(any())).thenReturn(new GenerateResponse().response(validJson()).usage(usage()));
        UUID cvId = UUID.randomUUID();
        UUID letterId = UUID.randomUUID();
        UUID applicationId = UUID.randomUUID();
        when(documentStoreApi.createDocument(any())).thenReturn(
                new GeneratedDocumentResponse().id(cvId),
                new GeneratedDocumentResponse().id(letterId));
        when(applicationTrackerApi.createApplication(any()))
                .thenReturn(new ApplicationRecordResponse().id(applicationId));

        GenerateCvCoverLetterResponse actual = service.generate(request);

        ArgumentCaptor<com.jobseekercopilot.generated.llmgateway.model.GenerateRequest> llmCaptor =
                ArgumentCaptor.forClass(com.jobseekercopilot.generated.llmgateway.model.GenerateRequest.class);
        verify(llmGatewayApi).generate(llmCaptor.capture());
        assertEquals("assembled prompt", llmCaptor.getValue().getPrompt());
        assertEquals(0.25, llmCaptor.getValue().getTemperature());

        ArgumentCaptor<com.jobseekercopilot.generated.documentstoreservice.model.CreateDocumentRequest> documentCaptor =
                ArgumentCaptor.forClass(com.jobseekercopilot.generated.documentstoreservice.model.CreateDocumentRequest.class);
        verify(documentStoreApi, org.mockito.Mockito.times(2)).createDocument(documentCaptor.capture());
        assertEquals("CV", documentCaptor.getAllValues().get(0).getDocumentType().getValue());
        assertEquals("COVER_LETTER", documentCaptor.getAllValues().get(1).getDocumentType().getValue());

        ArgumentCaptor<CreateApplicationRequest> applicationCaptor = ArgumentCaptor.forClass(CreateApplicationRequest.class);
        verify(applicationTrackerApi).createApplication(applicationCaptor.capture());
        assertEquals(cvId.toString(), applicationCaptor.getValue().getCvDocumentId());
        assertEquals(letterId.toString(), applicationCaptor.getValue().getCoverLetterDocumentId());
        assertEquals("Example Ltd", applicationCaptor.getValue().getCompanyName());

        assertEquals(applicationId.toString(), actual.getApplicationId());
        assertEquals(cvId.toString(), actual.getCvDocumentId());
        assertEquals("Tailored Developer CV", actual.getCvTitle());
        assertEquals("Tailored Developer CV", actual.getCvContent().lines().findFirst().orElseThrow());
        assertEquals("Dear Hiring Manager,", actual.getCoverLetterContent().lines().skip(2).findFirst().orElseThrow());

        ArgumentCaptor<PaymentBillingClient.ReservationRequest> reservationCaptor =
                ArgumentCaptor.forClass(PaymentBillingClient.ReservationRequest.class);
        verify(paymentBillingClient).reserve(org.mockito.Mockito.eq("user-123"), reservationCaptor.capture());
        assertEquals("CV_AND_COVER_LETTER_GENERATION", reservationCaptor.getValue().feature());
        assertEquals(5000L, reservationCaptor.getValue().estimatedTokens());

        ArgumentCaptor<PaymentBillingClient.CommitReservationRequest> commitCaptor =
                ArgumentCaptor.forClass(PaymentBillingClient.CommitReservationRequest.class);
        verify(paymentBillingClient).commit(org.mockito.Mockito.eq("user-123"), any(), commitCaptor.capture());
        assertEquals(7300L, commitCaptor.getValue().actualTokens());
        assertEquals("OPENAI", commitCaptor.getValue().provider());
    }

    @Test
    void doesNotCreateApplicationWhenSecondDocumentSaveFails() {
        UUID reservationId = UUID.randomUUID();
        when(paymentBillingClient.reserve(any(), any())).thenReturn(
                new PaymentBillingClient.ReservationResponse(reservationId, "user-123", 5000, 40000, "RESERVED"));
        when(llmGatewayApi.generate(any())).thenReturn(new GenerateResponse().response(validJson()).usage(usage()));
        when(documentStoreApi.createDocument(any()))
                .thenReturn(new GeneratedDocumentResponse().id(UUID.randomUUID()))
                .thenThrow(new RestClientException("down"));

        assertThrows(DownstreamServiceException.class, () -> service.generate(request));

        verify(documentStoreApi, org.mockito.Mockito.times(2)).createDocument(any());
        verifyNoInteractions(applicationTrackerApi);
        verify(paymentBillingClient, never()).commit(any(), any(), any());
        verify(paymentBillingClient).release("user-123", reservationId, "Document generation failed");
    }

    @Test
    void releasesReservationAndDoesNotCommitWhenLlmResponseCannotBeParsed() {
        UUID reservationId = UUID.randomUUID();
        when(paymentBillingClient.reserve(any(), any())).thenReturn(
                new PaymentBillingClient.ReservationResponse(reservationId, "user-123", 5000, 40000, "RESERVED"));
        when(llmGatewayApi.generate(any())).thenReturn(new GenerateResponse().response("not json").usage(usage()));

        assertThrows(InvalidLlmResponseException.class, () -> service.generate(request));

        verify(paymentBillingClient, never()).commit(any(), any(), any());
        verify(paymentBillingClient).release("user-123", reservationId, "Document generation failed");
        verifyNoInteractions(documentStoreApi, applicationTrackerApi);
    }

    @Test
    void releasesReservationWhenLlmGatewayFails() {
        UUID reservationId = UUID.randomUUID();
        when(paymentBillingClient.reserve(any(), any())).thenReturn(
                new PaymentBillingClient.ReservationResponse(reservationId, "user-123", 5000, 40000, "RESERVED"));
        when(llmGatewayApi.generate(any())).thenThrow(new RestClientException("down"));

        assertThrows(DownstreamServiceException.class, () -> service.generate(request));

        verify(paymentBillingClient).release("user-123", reservationId, "LLM generation failed");
        verifyNoInteractions(documentStoreApi, applicationTrackerApi);
    }

    private LlmUsage usage() {
        return new LlmUsage()
                .provider("OPENAI")
                .model("gpt-4.1-mini")
                .inputTokens(4200L)
                .outputTokens(3100L)
                .totalTokens(7300L);
    }

    static String validJson() {
        return """
                {
                  "cv": {
                    "title": "Tailored Developer CV",
                    "targetRole": "Developer",
                    "personalSummary": "A capable developer.",
                    "coreSkills": [{"name":"Java","evidence":"Built services"}],
                    "qualifications": [{"qualificationName":"BSc Computing","issuingBody":"Example University","status":"Completed","grade":"First","dateAchieved":"2024"}],
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
                  "generationNotes": {"assumptionsMade":[],"missingInformation":[],"tailoringSummary":"Focused on Java."}
                }
                """;
    }
}
