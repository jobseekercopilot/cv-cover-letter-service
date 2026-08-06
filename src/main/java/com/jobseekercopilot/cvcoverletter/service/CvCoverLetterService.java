package com.jobseekercopilot.cvcoverletter.service;

import com.jobseekercopilot.cvcoverletter.config.LlmProperties;
import com.jobseekercopilot.cvcoverletter.dto.ContactDetails;
import com.jobseekercopilot.cvcoverletter.dto.DraftGenerationEstimateResponse;
import com.jobseekercopilot.cvcoverletter.dto.DraftGenerationResponse;
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
import com.jobseekercopilot.generated.llmgateway.api.ModelGenerationApi;
import com.jobseekercopilot.generated.llmgateway.model.GenerationAudit;
import com.jobseekercopilot.generated.llmgateway.model.GenerationLimits;
import com.jobseekercopilot.generated.llmgateway.model.GenerationOutputContract;
import com.jobseekercopilot.generated.llmgateway.model.GenerationRequest;
import com.jobseekercopilot.generated.llmgateway.model.GenerationResponse;
import com.jobseekercopilot.generated.llmgateway.model.GenerationUsage;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;

@Service
@RequiredArgsConstructor
@Slf4j
public class CvCoverLetterService {
    private static final String GENERATION_FEATURE = "CV_AND_COVER_LETTER_GENERATION";
    private static final String REFERENCE_TYPE = "JOB_APPLICATION";
    private static final long MINIMUM_GENERATION_RESERVATION_TOKENS = 5_000L;
    private static final Pattern POLICY_VERSION =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{2,127}");

    private final PromptBuilderService promptBuilderService;
    private final GenerationInputNormalizer inputNormalizer;
    private final ModelGenerationApi llmGatewayApi;
    private final PaymentBillingClient paymentBillingClient;
    private final LlmProperties llmProperties;
    private final LlmResponseParser responseParser;
    private final CvDocumentRenderer cvRenderer;
    private final CoverLetterDocumentRenderer coverLetterRenderer;
    private final ValidatedClaimLedgerFactory claimLedgerFactory;
    private final GeneratedDocumentsApi documentStoreApi;
    private final ApplicationRecordsApi applicationTrackerApi;

    public DraftGenerationEstimateResponse estimateDraft(
            String ownerId, GenerateRequest request) {
        PreparedGeneration prepared = prepareGeneration(ownerId, request);
        return new DraftGenerationEstimateResponse(
                estimateTokens(prepared.prompt()));
    }

    public DraftGenerationResponse generateDraft(
            String ownerId,
            UUID operationId,
            GenerateRequest request) {
        if (operationId == null) {
            throw new IllegalArgumentException(
                    "Generation operation ID is required.");
        }
        long startedAt = System.nanoTime();
        PreparedGeneration prepared = prepareGeneration(ownerId, request);
        GenerationResponse llmResponse;
        try {
            llmResponse = invokeModel(prepared, operationId);
        } catch (RestClientException exception) {
            log.warn(
                    "Draft model request failed operationId={} jobId={} error={}",
                    operationId,
                    prepared.jobId(),
                    exception.getClass().getSimpleName(),
                    exception);
            throw new DownstreamServiceException(
                    "LLM gateway is unavailable", exception);
        }
        if (llmResponse == null) {
            throw new InvalidLlmResponseException(
                    "LLM gateway returned no response");
        }

        DraftContent draft = materializeDraft(prepared, llmResponse);
        GenerationUsage usage = llmResponse.getUsage();
        GenerationAudit audit = llmResponse.getAudit();
        log.info(
                "Bounded draft generation completed operationId={} jobId={} durationMs={}",
                operationId,
                prepared.jobId(),
                (System.nanoTime() - startedAt) / 1_000_000);
        return new DraftGenerationResponse(
                operationId,
                draft.documents().getCv().getTitle(),
                draft.documents().getCoverLetter().getTitle(),
                draft.cvContent(),
                draft.coverLetterContent(),
                draft.documents().getGenerationNotes(),
                prepared.prompt().getGenerationMetadata(),
                prepared.input().inputSchemaVersion(),
                prepared.input().warnings(),
                claimLedgerFactory.create(
                        operationId,
                        draft.documents().getClaims(),
                        prepared.parserVersion()),
                new DraftGenerationResponse.DraftGenerationUsage(
                        usage == null ? null : usage.getInputTokens(),
                        usage == null ? null : usage.getOutputTokens(),
                        usage == null ? null : usage.getTotalTokens()),
                new DraftGenerationResponse.DraftGenerationAudit(
                        audit.getModelId(),
                        audit.getModelDeploymentVersion(),
                        audit.getAdmissionPolicyVersion(),
                        audit.getPricingVersion(),
                        audit.getEstimatedInputTokensAtAdmission(),
                        audit.getEstimatedCostMicroUsd(),
                        audit.getCurrency()));
    }

    private PreparedGeneration prepareGeneration(
            String ownerId, GenerateRequest request) {
        NormalizedGenerationInput input =
                inputNormalizer.normalize(ownerId, request);
        String jobId = input.jobProvenance().getResourceId();
        long startedAt = System.nanoTime();
        CvCoverLetterPrompt prompt = promptBuilderService.buildPrompt(input);
        String parserVersion = responseParser.parserVersion(
                prompt.getOutputSchema());
        log.info(
                "Bounded prompt prepared jobId={} promptRelease={} bundleVersion={} schemaId={} schemaVersion={} estimatedTokens={} durationMs={}",
                jobId,
                prompt.getGenerationMetadata().releaseId(),
                prompt.getGenerationMetadata().bundleVersion(),
                prompt.getGenerationMetadata().schemaId(),
                prompt.getGenerationMetadata().schemaVersion(),
                estimateTokens(prompt),
                (System.nanoTime() - startedAt) / 1_000_000);
        GenerationRequest llmRequest = new GenerationRequest()
                .contractVersion(GenerationRequest.ContractVersionEnum._2_0)
                .task(prompt.getTaskType())
                .trustedInstructions(prompt.getTrustedInstructions())
                .untrustedInput(prompt.getUntrustedInput())
                .output(new GenerationOutputContract()
                        .format(GenerationOutputContract.FormatEnum.JSON_SCHEMA)
                        .schemaId(prompt.getGenerationMetadata().schemaId())
                        .schemaVersion(
                                prompt.getGenerationMetadata().schemaVersion())
                        .jsonSchema(prompt.getOutputSchema()))
                .limits(new GenerationLimits()
                        .temperature(llmProperties.getTemperature())
                        .maxOutputTokens(llmProperties.getMaxTokens()));
        return new PreparedGeneration(
                input, prompt, llmRequest, jobId, parserVersion);
    }

    private GenerationResponse invokeModel(
            PreparedGeneration prepared, UUID operationId) {
        long startedAt = System.nanoTime();
        log.info(
                "Bounded model request started operationId={} jobId={} taskType={}",
                operationId,
                prepared.jobId(),
                prepared.prompt().getTaskType());
        GenerationResponse response =
                llmGatewayApi.generateV2(prepared.llmRequest());
        GenerationUsage usage = response == null ? null : response.getUsage();
        log.info(
                "Bounded model response received operationId={} jobId={} finishReason={} inputTokens={} outputTokens={} totalTokens={} durationMs={}",
                operationId,
                prepared.jobId(),
                response == null ? null : response.getFinishReason(),
                usage == null ? null : usage.getInputTokens(),
                usage == null ? null : usage.getOutputTokens(),
                usage == null ? null : usage.getTotalTokens(),
                (System.nanoTime() - startedAt) / 1_000_000);
        return response;
    }

    private DraftContent materializeDraft(
            PreparedGeneration prepared, GenerationResponse llmResponse) {
        validateGenerationResponse(llmResponse, prepared.prompt());
        GeneratedApplicationDocuments documents = responseParser.parse(
                llmResponse.getOutput(),
                prepared.prompt().getOutputSchema(),
                prepared.prompt().getEvidenceCatalog());
        String cvContent = cvRenderer.render(
                documents.getCv(), prepared.input().contact());
        String coverLetterContent = coverLetterRenderer.render(
                documents.getCoverLetter(), prepared.input().contact());
        return new DraftContent(
                documents, cvContent, coverLetterContent);
    }

    private record PreparedGeneration(
            NormalizedGenerationInput input,
            CvCoverLetterPrompt prompt,
            GenerationRequest llmRequest,
            String jobId,
            String parserVersion) {
    }

    private record DraftContent(
            GeneratedApplicationDocuments documents,
            String cvContent,
            String coverLetterContent) {
    }

    public GenerateCvCoverLetterResponse generate(String ownerId, GenerateRequest request) {
        long generationStartedAt = System.nanoTime();
        NormalizedGenerationInput input = inputNormalizer.normalize(ownerId, request);
        String userId = input.ownerId();
        String jobId = input.jobProvenance().getResourceId();
        log.info("CV/cover letter generation request received userId={} jobId={}", userId, jobId);
        long promptStartedAt = System.nanoTime();
        log.info("Prompt build started userId={} jobId={}", userId, jobId);
        CvCoverLetterPrompt prompt = promptBuilderService.buildPrompt(input);
        String parserVersion = responseParser.parserVersion(
                prompt.getOutputSchema());
        log.info("Prompt build completed userId={} jobId={} llmContractVersion=2.0 promptRelease={} bundleVersion={} templateVersion={} rulesVersion={} schemaId={} schemaVersion={} evaluationPolicyVersion={} bundleSha256={} trustedInstructionCharacters={} untrustedInputCharacters={} estimatedTokens={} durationMs={}",
                userId,
                jobId,
                prompt.getGenerationMetadata().releaseId(),
                prompt.getGenerationMetadata().bundleVersion(),
                prompt.getGenerationMetadata().templateVersion(),
                prompt.getGenerationMetadata().rulesVersion(),
                prompt.getGenerationMetadata().schemaId(),
                prompt.getGenerationMetadata().schemaVersion(),
                prompt.getGenerationMetadata().evaluationPolicyVersion(),
                prompt.getGenerationMetadata().bundleSha256(),
                prompt.getTrustedInstructions().length(),
                prompt.getUntrustedInput().length(),
                estimateTokens(prompt),
                (System.nanoTime() - promptStartedAt) / 1_000_000);

        GenerationRequest llmRequest = new GenerationRequest()
                .contractVersion(GenerationRequest.ContractVersionEnum._2_0)
                .task(prompt.getTaskType())
                .trustedInstructions(prompt.getTrustedInstructions())
                .untrustedInput(prompt.getUntrustedInput())
                .output(new GenerationOutputContract()
                        .format(GenerationOutputContract.FormatEnum.JSON_SCHEMA)
                        .schemaId(prompt.getGenerationMetadata().schemaId())
                        .schemaVersion(prompt.getGenerationMetadata().schemaVersion())
                        .jsonSchema(prompt.getOutputSchema()))
                .limits(new GenerationLimits()
                        .temperature(llmProperties.getTemperature())
                        .maxOutputTokens(llmProperties.getMaxTokens()));

        long reservationStartedAt = System.nanoTime();
        log.info("Billing reservation started userId={} jobId={} estimatedTokens={}",
                userId,
                jobId,
                estimateTokens(prompt));
        PaymentBillingClient.ReservationResponse reservation = paymentBillingClient.reserve(
                userId,
                PaymentBillingClient.ReservationRequest.builder()
                        .feature(GENERATION_FEATURE)
                        .estimatedTokens(estimateTokens(prompt))
                        .operationKey("cv-generation:" + UUID.randomUUID())
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

        GenerationResponse llmResponse;
        try {
            long llmStartedAt = System.nanoTime();
            log.info("LLM request started userId={} jobId={} taskType={} maxTokens={} temperature={}",
                    userId,
                    jobId,
                    llmProperties.getTaskType(),
                    llmProperties.getMaxTokens(),
                    llmProperties.getTemperature());
            llmResponse = llmGatewayApi.generateV2(llmRequest);
            GenerationUsage usage = llmResponse == null ? null : llmResponse.getUsage();
            log.info("LLM response received userId={} jobId={} llmContractVersion={} finishReason={} schemaId={} schemaVersion={} parserVersion={} inputTokens={} outputTokens={} totalTokens={} durationMs={}",
                    userId,
                    jobId,
                    llmResponse == null ? null : llmResponse.getContractVersion(),
                    llmResponse == null ? null : llmResponse.getFinishReason(),
                    llmResponse == null ? null : llmResponse.getSchemaId(),
                    llmResponse == null ? null : llmResponse.getSchemaVersion(),
                    parserVersion,
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
            DownstreamServiceException generationFailure =
                    new DownstreamServiceException("LLM gateway is unavailable", exception);
            releaseReservationAfterFailure(
                    userId,
                    reservation.reservationId(),
                    "LLM generation failed",
                    generationFailure);
            throw generationFailure;
        }
        if (llmResponse == null) {
            log.warn("LLM gateway returned no response userId={} jobId={}", userId, jobId);
            InvalidLlmResponseException generationFailure =
                    new InvalidLlmResponseException("LLM gateway returned no response");
            releaseReservationAfterFailure(
                    userId,
                    reservation.reservationId(),
                    "LLM gateway returned no response",
                    generationFailure);
            throw generationFailure;
        }

        try {
            validateGenerationResponse(llmResponse, prompt);
            GenerationAudit generationAudit = llmResponse.getAudit();
            log.info("LLM generation provenance accepted userId={} jobId={} llmContractVersion={} modelId={} modelDeploymentVersion={} admissionPolicyVersion={} pricingVersion={} estimatedInputTokensAtAdmission={} estimatedCostMicroUsd={} currency={} promptRelease={} bundleVersion={} templateVersion={} rulesVersion={} schemaId={} schemaVersion={} evaluationPolicyVersion={} parserVersion={}",
                    userId,
                    jobId,
                    llmResponse.getContractVersion(),
                    generationAudit.getModelId(),
                    generationAudit.getModelDeploymentVersion(),
                    generationAudit.getAdmissionPolicyVersion(),
                    generationAudit.getPricingVersion(),
                    generationAudit.getEstimatedInputTokensAtAdmission(),
                    generationAudit.getEstimatedCostMicroUsd(),
                    generationAudit.getCurrency(),
                    prompt.getGenerationMetadata().releaseId(),
                    prompt.getGenerationMetadata().bundleVersion(),
                    prompt.getGenerationMetadata().templateVersion(),
                    prompt.getGenerationMetadata().rulesVersion(),
                    prompt.getGenerationMetadata().schemaId(),
                    prompt.getGenerationMetadata().schemaVersion(),
                    prompt.getGenerationMetadata().evaluationPolicyVersion(),
                    parserVersion);
            GeneratedApplicationDocuments documents =
                    responseParser.parse(
                            llmResponse.getOutput(),
                            prompt.getOutputSchema(),
                            prompt.getEvidenceCatalog());
            if (documents.getClaims() != null) {
                log.info(
                        "LLM claim evidence accepted userId={} jobId={} policyVersion={} evidenceRecords={} claims={}",
                        userId,
                        jobId,
                        ClaimEvidenceValidator.POLICY_VERSION,
                        prompt.getEvidenceCatalog().records().size(),
                        documents.getClaims().size());
            }
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

            commitReservation(
                    userId,
                    reservation.reservationId(),
                    llmResponse.getUsage(),
                    generationAudit);
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
                    .generationMetadata(prompt.getGenerationMetadata())
                    .inputSchemaVersion(input.inputSchemaVersion())
                    .inputWarnings(input.warnings())
                    .build();
        } catch (RuntimeException exception) {
            releaseReservationAfterFailure(
                    userId,
                    reservation.reservationId(),
                    "Document generation failed",
                    exception);
            throw exception;
        }
    }

    private long estimateTokens(CvCoverLetterPrompt prompt) {
        long requestCharacters = (long) prompt.getTrustedInstructions().length()
                + prompt.getUntrustedInput().length()
                + prompt.getOutputSchema().toString().length();
        long estimatedInputTokens = Math.max(1, (long) Math.ceil(requestCharacters / 4.0));
        long estimatedOutputTokens = Math.max(0, llmProperties.getMaxTokens());
        return Math.max(MINIMUM_GENERATION_RESERVATION_TOKENS, estimatedInputTokens + estimatedOutputTokens);
    }

    private void validateGenerationResponse(
            GenerationResponse response,
            CvCoverLetterPrompt prompt
    ) {
        if (!"2.0".equals(response.getContractVersion())) {
            throw new InvalidLlmResponseException("LLM gateway returned an unexpected contract version");
        }
        if (response.getFinishReason() != GenerationResponse.FinishReasonEnum.COMPLETED) {
            throw new InvalidLlmResponseException("LLM generation did not complete safely");
        }
        if (!prompt.getGenerationMetadata().schemaId().equals(response.getSchemaId())
                || !prompt.getGenerationMetadata().schemaVersion().equals(response.getSchemaVersion())) {
            throw new InvalidLlmResponseException("LLM gateway returned unexpected output schema metadata");
        }
        if (response.getOutput() == null || response.getOutput().isBlank()) {
            throw new InvalidLlmResponseException("LLM gateway returned no generated output");
        }
        validateGenerationAudit(response.getAudit());
    }

    private void validateGenerationAudit(GenerationAudit audit) {
        if (audit == null
                || !StringUtils.hasText(audit.getModelId())
                || audit.getModelId().length() > 128
                || audit.getModelId().chars().anyMatch(Character::isWhitespace)
                || !validPolicyVersion(audit.getModelDeploymentVersion())
                || !validPolicyVersion(audit.getAdmissionPolicyVersion())
                || !validPolicyVersion(audit.getPricingVersion())
                || audit.getEstimatedInputTokensAtAdmission() == null
                || audit.getEstimatedInputTokensAtAdmission() < 0
                || audit.getEstimatedCostMicroUsd() == null
                || audit.getEstimatedCostMicroUsd() < 0
                || !"USD".equals(audit.getCurrency())) {
            throw new InvalidLlmResponseException(
                    "LLM gateway returned invalid generation audit metadata");
        }
    }

    private boolean validPolicyVersion(String value) {
        return StringUtils.hasText(value) && POLICY_VERSION.matcher(value).matches();
    }

    private void commitReservation(
            String userId,
            UUID reservationId,
            GenerationUsage usage,
            GenerationAudit audit
    ) {
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
                .model(audit.getModelId())
                .inputTokens(usage == null ? null : usage.getInputTokens())
                .outputTokens(usage == null ? null : usage.getOutputTokens())
                .description("CV and cover letter generation")
                .build());
        log.info("Billing commit succeeded userId={} reservationId={}", userId, reservationId);
    }

    private void releaseReservationAfterFailure(
            String userId,
            UUID reservationId,
            String reason,
            RuntimeException generationFailure) {
        try {
            log.info("Billing release started userId={} reservationId={} reason={}", userId, reservationId, reason);
            paymentBillingClient.release(userId, reservationId, reason);
            log.info("Billing release succeeded userId={} reservationId={}", userId, reservationId);
        } catch (DownstreamServiceException exception) {
            log.error(
                    "Billing compensation unresolved after generation failure reservationId={}",
                    reservationId,
                    exception);
            DownstreamServiceException unresolved = new DownstreamServiceException(
                    "Document generation failed and billing compensation is unresolved",
                    exception);
            unresolved.addSuppressed(generationFailure);
            throw unresolved;
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
            GeneratedDocumentResponse response =
                    documentStoreApi.createDocument(documentRequest, input.ownerId());
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
