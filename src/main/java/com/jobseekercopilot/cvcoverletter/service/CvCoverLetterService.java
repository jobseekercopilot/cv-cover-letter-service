package com.jobseekercopilot.cvcoverletter.service;

import com.jobseekercopilot.cvcoverletter.config.LlmProperties;
import com.jobseekercopilot.cvcoverletter.dto.ContactDetails;
import com.jobseekercopilot.cvcoverletter.dto.GenerateRequest;
import com.jobseekercopilot.cvcoverletter.dto.GenerateCvCoverLetterResponse;
import com.jobseekercopilot.cvcoverletter.dto.GeneratedApplicationDocuments;
import com.jobseekercopilot.cvcoverletter.exception.DownstreamServiceException;
import com.jobseekercopilot.cvcoverletter.exception.InvalidLlmResponseException;
import com.jobseekercopilot.cvcoverletter.model.CvCoverLetterPrompt;
import com.jobseekercopilot.cvcoverletter.model.NormalizedGenerationInput;
import com.jobseekercopilot.generated.applicationtrackerservice.api.ApplicationRecordsApi;
import com.jobseekercopilot.generated.applicationtrackerservice.model.ApplicationRecordResponse;
import com.jobseekercopilot.generated.applicationtrackerservice.model.CreateApplicationRequest;
import com.jobseekercopilot.generated.documentstoreservice.api.GeneratedDocumentsApi;
import com.jobseekercopilot.generated.documentstoreservice.model.CreateDocumentRequest.DocumentTypeEnum;
import com.jobseekercopilot.generated.documentstoreservice.model.GeneratedDocumentResponse;
import com.jobseekercopilot.generated.llmgateway.api.LlmGenerationApi;
import com.jobseekercopilot.generated.llmgateway.model.GenerateResponse;
import com.jobseekercopilot.generated.llmgateway.model.LlmUsage;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

@Service
@RequiredArgsConstructor
@Slf4j
public class CvCoverLetterService {
    private static final String GENERATION_FEATURE = "CV_AND_COVER_LETTER_GENERATION";
    private static final String REFERENCE_TYPE = "JOB_APPLICATION";
    private static final long MINIMUM_GENERATION_RESERVATION_TOKENS = 5_000L;

    private final PromptBuilderService promptBuilderService;
    private final GenerationInputNormalizer inputNormalizer;
    private final LlmGenerationApi llmGatewayApi;
    private final PaymentBillingClient paymentBillingClient;
    private final LlmProperties llmProperties;
    private final LlmResponseParser responseParser;
    private final CvDocumentRenderer cvRenderer;
    private final CoverLetterDocumentRenderer coverLetterRenderer;
    private final GeneratedDocumentsApi documentStoreApi;
    private final ApplicationRecordsApi applicationTrackerApi;

    public GenerateCvCoverLetterResponse generate(String ownerId, GenerateRequest request) {
        long generationStartedAt = System.nanoTime();
        NormalizedGenerationInput input = inputNormalizer.normalize(ownerId, request);
        String userId = input.ownerId();
        String jobId = input.jobProvenance().getResourceId();
        log.info("CV/cover letter generation request received userId={} jobId={}", userId, jobId);
        long promptStartedAt = System.nanoTime();
        log.info("Prompt build started userId={} jobId={}", userId, jobId);
        CvCoverLetterPrompt prompt = promptBuilderService.buildPrompt(input);
        log.info("Prompt build completed userId={} jobId={} estimatedTokens={} durationMs={}",
                userId,
                jobId,
                estimateTokens(prompt.getFinalPrompt()),
                (System.nanoTime() - promptStartedAt) / 1_000_000);

        com.jobseekercopilot.generated.llmgateway.model.GenerateRequest llmRequest =
                new com.jobseekercopilot.generated.llmgateway.model.GenerateRequest()
                .taskType(llmProperties.getTaskType())
                .prompt(prompt.getFinalPrompt())
                .temperature(llmProperties.getTemperature())
                .maxTokens(llmProperties.getMaxTokens());

        long reservationStartedAt = System.nanoTime();
        log.info("Billing reservation started userId={} jobId={} estimatedTokens={}",
                userId,
                jobId,
                estimateTokens(prompt.getFinalPrompt()));
        PaymentBillingClient.ReservationResponse reservation = paymentBillingClient.reserve(
                userId,
                PaymentBillingClient.ReservationRequest.builder()
                        .feature(GENERATION_FEATURE)
                        .estimatedTokens(estimateTokens(prompt.getFinalPrompt()))
                        .referenceType(REFERENCE_TYPE)
                        .referenceId(jobId)
                        .build());
        if (reservation == null || reservation.reservationId() == null) {
            log.error("Billing reservation failed userId={} jobId={} error=MissingReservationId", userId, jobId);
            throw new DownstreamServiceException("Payment service returned no reservation", null);
        }
        log.info("Billing reservation succeeded userId={} reservationId={} reservedTokens={} durationMs={}",
                userId,
                reservation.reservationId(),
                reservation.reservedTokens(),
                (System.nanoTime() - reservationStartedAt) / 1_000_000);

        GenerateResponse llmResponse;
        try {
            long llmStartedAt = System.nanoTime();
            log.info("LLM request started userId={} jobId={} taskType={} maxTokens={} temperature={}",
                    userId,
                    jobId,
                    llmProperties.getTaskType(),
                    llmProperties.getMaxTokens(),
                    llmProperties.getTemperature());
            llmResponse = llmGatewayApi.generate(llmRequest);
            LlmUsage usage = llmResponse == null ? null : llmResponse.getUsage();
            log.info("LLM request completed userId={} jobId={} inputTokens={} outputTokens={} totalTokens={} durationMs={}",
                    userId,
                    jobId,
                    usage == null ? null : usage.getInputTokens(),
                    usage == null ? null : usage.getOutputTokens(),
                    usage == null ? null : usage.getTotalTokens(),
                    (System.nanoTime() - llmStartedAt) / 1_000_000);
        } catch (RestClientException exception) {
            log.warn("LLM request failed userId={} jobId={} error={}",
                    userId,
                    jobId,
                    exception.getClass().getSimpleName(),
                    exception);
            releaseReservationAfterFailure(userId, reservation.reservationId(), "LLM generation failed");
            throw new DownstreamServiceException("LLM gateway is unavailable", exception);
        }
        if (llmResponse == null) {
            log.warn("LLM gateway returned no response userId={} jobId={}", userId, jobId);
            releaseReservationAfterFailure(userId, reservation.reservationId(), "LLM gateway returned no response");
            throw new InvalidLlmResponseException("LLM gateway returned no response");
        }

        try {
            GeneratedApplicationDocuments documents = responseParser.parse(llmResponse.getResponse());
            String cvContent = cvRenderer.render(documents.getCv(), input.contact());
            String coverLetterContent = coverLetterRenderer.render(documents.getCoverLetter(), input.contact());

            GeneratedDocumentResponse savedCv = saveDocument(input, DocumentTypeEnum.CV,
                    documents.getCv().getTitle(), cvContent);
            GeneratedDocumentResponse savedCoverLetter = saveDocument(input, DocumentTypeEnum.COVER_LETTER,
                    documents.getCoverLetter().getTitle(), coverLetterContent);
            requireDocumentId(savedCv, "CV");
            requireDocumentId(savedCoverLetter, "cover letter");

            ApplicationRecordResponse application = createApplication(
                    input, savedCv.getId().toString(), savedCoverLetter.getId().toString());
            if (application == null || application.getId() == null) {
                throw new DownstreamServiceException("Application tracker returned no application ID", null);
            }

            commitReservation(userId, reservation.reservationId(), llmResponse.getUsage());
            log.info("CV/cover letter generation succeeded userId={} jobId={} applicationId={} cvDocumentId={} coverLetterDocumentId={} durationMs={}",
                    userId,
                    jobId,
                    application.getId(),
                    savedCv.getId(),
                    savedCoverLetter.getId(),
                    (System.nanoTime() - generationStartedAt) / 1_000_000);

            return GenerateCvCoverLetterResponse.builder()
                    .applicationId(application.getId().toString())
                    .cvDocumentId(savedCv.getId().toString())
                    .coverLetterDocumentId(savedCoverLetter.getId().toString())
                    .cvTitle(documents.getCv().getTitle())
                    .coverLetterTitle(documents.getCoverLetter().getTitle())
                    .cvContent(cvContent)
                    .coverLetterContent(coverLetterContent)
                    .generationNotes(documents.getGenerationNotes())
                    .inputSchemaVersion(input.inputSchemaVersion())
                    .inputWarnings(input.warnings())
                    .build();
        } catch (RuntimeException exception) {
            releaseReservationAfterFailure(userId, reservation.reservationId(), "Document generation failed");
            throw exception;
        }
    }

    private long estimateTokens(String prompt) {
        long estimatedInputTokens = Math.max(1, (long) Math.ceil(prompt.length() / 4.0));
        long estimatedOutputTokens = Math.max(0, llmProperties.getMaxTokens());
        return Math.max(MINIMUM_GENERATION_RESERVATION_TOKENS, estimatedInputTokens + estimatedOutputTokens);
    }

    private void commitReservation(String userId, UUID reservationId, LlmUsage usage) {
        long actualTokens = usage == null || usage.getTotalTokens() == null
                ? 0
                : Math.max(0, usage.getTotalTokens());
        if (usage == null || usage.getTotalTokens() == null || usage.getTotalTokens() == 0) {
            log.warn("LLM generation completed without usable token usage; committing zero actual tokens");
        }
        log.info("Billing commit started userId={} reservationId={} actualTokens={} inputTokens={} outputTokens={}",
                userId,
                reservationId,
                actualTokens,
                usage == null ? null : usage.getInputTokens(),
                usage == null ? null : usage.getOutputTokens());
        paymentBillingClient.commit(userId, reservationId, PaymentBillingClient.CommitReservationRequest.builder()
                .actualTokens(actualTokens)
                .provider(usage == null ? null : usage.getProvider())
                .model(usage == null ? null : usage.getModel())
                .inputTokens(usage == null ? null : usage.getInputTokens())
                .outputTokens(usage == null ? null : usage.getOutputTokens())
                .description("CV and cover letter generation")
                .build());
        log.info("Billing commit succeeded userId={} reservationId={}", userId, reservationId);
    }

    private void releaseReservationAfterFailure(String userId, UUID reservationId, String reason) {
        try {
            log.info("Billing release started userId={} reservationId={} reason={}", userId, reservationId, reason);
            paymentBillingClient.release(userId, reservationId, reason);
            log.info("Billing release succeeded userId={} reservationId={}", userId, reservationId);
        } catch (DownstreamServiceException exception) {
            log.error("Failed to release AI token reservation {} after generation failure", reservationId, exception);
        }
    }

    private GeneratedDocumentResponse saveDocument(NormalizedGenerationInput input, DocumentTypeEnum type,
                                                    String title, String content) {
        com.jobseekercopilot.generated.documentstoreservice.model.CreateDocumentRequest documentRequest =
                new com.jobseekercopilot.generated.documentstoreservice.model.CreateDocumentRequest()
                        .userId(input.ownerId())
                        .jobId(input.jobProvenance().getResourceId())
                        .documentType(type)
                        .title(title)
                        .content(content);
        try {
            long startedAt = System.nanoTime();
            log.info("Document save started userId={} jobId={} documentType={}",
                    input.ownerId(),
                    input.jobProvenance().getResourceId(),
                    type);
            GeneratedDocumentResponse response = documentStoreApi.createDocument(documentRequest);
            if (response == null) {
                throw new DownstreamServiceException("Document store failed to save " + type, null);
            }
            log.info("Document save succeeded userId={} jobId={} documentType={} documentId={} durationMs={}",
                    input.ownerId(),
                    input.jobProvenance().getResourceId(),
                    type,
                    response.getId(),
                    (System.nanoTime() - startedAt) / 1_000_000);
            return response;
        } catch (RestClientException exception) {
            log.warn("Document save failed userId={} jobId={} documentType={} error={}",
                    input.ownerId(),
                    input.jobProvenance().getResourceId(),
                    type,
                    exception.getClass().getSimpleName(),
                    exception);
            throw new DownstreamServiceException("Document store failed to save " + type, exception);
        }
    }

    private void requireDocumentId(GeneratedDocumentResponse document, String name) {
        if (document.getId() == null) {
            throw new DownstreamServiceException("Document store returned no ID for the " + name, null);
        }
    }

    private ApplicationRecordResponse createApplication(
            NormalizedGenerationInput input, String cvId, String coverLetterId) {
        CreateApplicationRequest applicationRequest = new CreateApplicationRequest()
                .userId(input.ownerId())
                .jobId(input.jobProvenance().getResourceId())
                .canonicalJobId(input.jobProvenance().getResourceId())
                .jobTitle(input.job().title())
                .companyName(input.job().company())
                .location(input.job().location())
                .cvDocumentId(cvId)
                .coverLetterDocumentId(coverLetterId);
        try {
            long startedAt = System.nanoTime();
            log.info("Application tracker create started userId={} jobId={} cvDocumentId={} coverLetterDocumentId={}",
                    input.ownerId(),
                    input.jobProvenance().getResourceId(),
                    cvId,
                    coverLetterId);
            ApplicationRecordResponse response = applicationTrackerApi.createApplication(applicationRequest);
            log.info("Application tracker create succeeded userId={} jobId={} applicationId={} durationMs={}",
                    input.ownerId(),
                    input.jobProvenance().getResourceId(),
                    response == null ? null : response.getId(),
                    (System.nanoTime() - startedAt) / 1_000_000);
            return response;
        } catch (RestClientException exception) {
            log.warn("Application tracker create failed userId={} jobId={} error={}",
                    input.ownerId(),
                    input.jobProvenance().getResourceId(),
                    exception.getClass().getSimpleName(),
                    exception);
            throw new DownstreamServiceException("Application tracker is unavailable", exception);
        }
    }
}
