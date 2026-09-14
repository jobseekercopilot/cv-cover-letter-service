package com.jobseekercopilot.cvcoverletter.service;

import static com.jobseekercopilot.cvcoverletter.GenerationInputFixtures.validRequest;
import static com.jobseekercopilot.cvcoverletter.GenerationInputFixtures.validSelectedRequest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jobseekercopilot.cvcoverletter.config.LlmProperties;
import com.jobseekercopilot.cvcoverletter.config.PromptBundleProperties;
import com.jobseekercopilot.cvcoverletter.dto.DraftGenerationResponse;
import com.jobseekercopilot.cvcoverletter.dto.DraftOutputType;
import com.jobseekercopilot.cvcoverletter.dto.EvidenceCategory;
import com.jobseekercopilot.cvcoverletter.dto.GenerateCvCoverLetterResponse;
import com.jobseekercopilot.cvcoverletter.dto.GenerateRequest;
import com.jobseekercopilot.cvcoverletter.dto.PromptGenerationMetadata;
import com.jobseekercopilot.cvcoverletter.dto.RejectedGenerationReplayResponse;
import com.jobseekercopilot.cvcoverletter.dto.RejectedSelectedGenerationReplayResponse;
import com.jobseekercopilot.cvcoverletter.dto.SelectedDraftGenerationResponse;
import com.jobseekercopilot.cvcoverletter.exception.DownstreamServiceException;
import com.jobseekercopilot.cvcoverletter.exception.InvalidLlmResponseException;
import com.jobseekercopilot.cvcoverletter.exception.RejectedGenerationQuarantineException;
import com.jobseekercopilot.cvcoverletter.model.CvCoverLetterPrompt;
import com.jobseekercopilot.cvcoverletter.model.NormalizedGenerationInput;
import com.jobseekercopilot.cvcoverletter.quarantine.RejectedGenerationQuarantineService;
import com.jobseekercopilot.cvcoverletter.quarantine.RejectedGenerationArtifact;
import com.jobseekercopilot.cvcoverletter.quarantine.RejectedGenerationCaptureContext;
import com.jobseekercopilot.cvcoverletter.quarantine.RejectedGenerationDiagnostic;
import com.jobseekercopilot.cvcoverletter.quarantine.RejectedGenerationReplayAuditEvent;
import com.jobseekercopilot.generated.applicationtrackerservice.api.ApplicationRecordsApi;
import com.jobseekercopilot.generated.applicationtrackerservice.model.ApplicationRecordResponse;
import com.jobseekercopilot.generated.applicationtrackerservice.model.CreateApplicationRequest;
import com.jobseekercopilot.generated.documentstoreservice.api.GeneratedDocumentsApi;
import com.jobseekercopilot.generated.documentstoreservice.model.GeneratedDocumentResponse;
import com.jobseekercopilot.generated.llmgateway.api.ModelGenerationApi;
import com.jobseekercopilot.generated.llmgateway.model.GenerationAudit;
import com.jobseekercopilot.generated.llmgateway.model.GenerationRequest;
import com.jobseekercopilot.generated.llmgateway.model.GenerationResponse;
import com.jobseekercopilot.generated.llmgateway.model.GenerationUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.client.RestClientException;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.UUID;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class CvCoverLetterServiceTest {

    @Mock private PromptBuilderService promptBuilderService;
    @Mock private GenerationInputNormalizer inputNormalizer;
    @Mock private ModelGenerationApi llmGatewayApi;
    @Mock private PaymentBillingClient paymentBillingClient;
    @Mock private GeneratedDocumentsApi documentStoreApi;
    @Mock private ApplicationRecordsApi applicationTrackerApi;
    @Mock private RejectedGenerationQuarantineService quarantineService;

    private CvCoverLetterService service;
    private GenerateRequest request;
    private NormalizedGenerationInput normalizedInput;

    @BeforeEach
    void setUp() throws Exception {
        LlmProperties properties = new LlmProperties();
        properties.setTaskType("CV_COVER_LETTER_GENERATION");
        properties.setTemperature(0.25);
        properties.setMaxTokens(2500);
        // Existing cases assert single-attempt semantics; retry behaviour is
        // exercised explicitly in the dedicated validation-retry tests, which
        // build their own service instance with retries enabled.
        properties.setMaxValidationRetries(0);
        service = new CvCoverLetterService(promptBuilderService, inputNormalizer, llmGatewayApi, paymentBillingClient, properties,
                new LlmResponseParser(
                        new ObjectMapper(),
                        new ClaimEvidenceValidator(),
                        new GeneratedDocumentQualityValidator()),
                new CvDocumentRenderer(),
                new CoverLetterDocumentRenderer(),
                new ValidatedClaimLedgerFactory(),
                documentStoreApi,
                applicationTrackerApi,
                quarantineService,
                new DeterministicCvFallbackService(new ObjectMapper()));

        request = validRequest();
        normalizedInput = new GenerationInputNormalizer().normalize("user-123", request);
        lenient().when(inputNormalizer.normalize("user-123", request))
                .thenReturn(normalizedInput);
        lenient().when(promptBuilderService.buildPrompt(normalizedInput))
                .thenReturn(prompt(
                        activeOutputSchema(), promptMetadata()));
        lenient().when(paymentBillingClient.reserve(any(), any())).thenReturn(
                new PaymentBillingClient.ReservationResponse(UUID.randomUUID(), "user-123", 5000, 40000, "RESERVED"));
    }

    @Test
    void estimatesWithoutCallingModelOrOwningSideEffects() {
        var estimate = service.estimateDraft("user-123", request);

        assertEquals(5000L, estimate.estimatedTokens());
        verifyNoInteractions(
                llmGatewayApi,
                paymentBillingClient,
                documentStoreApi,
                applicationTrackerApi);
    }

    @Test
    void returnsBoundedDraftAndUsageWithoutPaymentStorageOrApplicationSideEffects() {
        UUID operationId = UUID.randomUUID();
        when(llmGatewayApi.generateV2(any())).thenReturn(
                successfulResponse(activeValidJson()));

        DraftGenerationResponse actual =
                service.generateDraft("user-123", operationId, request);

        assertEquals(operationId, actual.operationId());
        assertEquals("Java Developer CV", actual.cvTitle());
        assertEquals(
                "Java Developer CV",
                actual.cvContent().lines().findFirst().orElseThrow());
        assertEquals(7300L, actual.usage().totalTokens());
        assertEquals(
                "gpt-4.1-mini-2025-04-14",
                actual.audit().modelId());
        assertEquals("1.0", actual.inputSchemaVersion());
        assertEquals(10, actual.claimLedger().claims().size());
        assertEquals(64, actual.claimLedger().ledgerSha256().length());
        assertEquals("2.25.0", actual.claimLedger().policyVersion());
        assertEquals("3.5.2", actual.claimLedger().parserVersion());
        verify(llmGatewayApi).generateV2(any());
        verifyNoInteractions(
                paymentBillingClient,
                documentStoreApi,
                applicationTrackerApi);
    }

    @ParameterizedTest
    @MethodSource("selectedOutputs")
    void generatesExactlyOneSelectedDraftWithoutOwningSideEffects(
            DraftOutputType outputType) throws Exception {
        var selectedRequest = validSelectedRequest(outputType);
        if (outputType == DraftOutputType.CV) {
            selectedRequest.getEvidenceSnapshot().setSectionOrder(
                    List.of(EvidenceCategory.OTHER));
            selectedRequest.getEvidenceSnapshot().getSelections().get(0)
                    .setCategory(EvidenceCategory.OTHER);
        }
        var selectedInput = new GenerationInputNormalizer()
                .normalizeSelected("user-123", outputType, selectedRequest);
        CvCoverLetterPrompt selectedPrompt = selectedPrompt(
                selectedInput, outputType);
        when(inputNormalizer.normalizeSelected(
                "user-123", outputType, selectedRequest))
                .thenReturn(selectedInput);
        when(promptBuilderService.buildPrompt(selectedInput, outputType))
                .thenReturn(selectedPrompt);
        when(llmGatewayApi.generateV2(any())).thenReturn(
                successfulResponse(outputType == DraftOutputType.CV
                                ? selectedCvJson()
                                : selectedCoverLetterJson())
                        .audit(generationAudit()
                                .providerAttemptCount(2)
                                .automaticRetryCount(1)
                                .retryReason("RATE_LIMITED"))
                        .schemaId(selectedPrompt.getGenerationMetadata().schemaId())
                        .schemaVersion(selectedPrompt.getGenerationMetadata().schemaVersion()));
        UUID operationId = UUID.randomUUID();

        SelectedDraftGenerationResponse result = service.generateSelectedDraft(
                "user-123",
                operationId,
                outputType,
                selectedRequest);

        assertEquals(operationId, result.operationId());
        assertEquals(outputType, result.outputType());
        assertEquals("2.0", result.inputSchemaVersion());
        assertEquals("2.25.0", result.claimLedger().policyVersion());
        assertEquals(
                outputType == DraftOutputType.CV
                        ? "Java Developer CV"
                        : "Java Developer Cover Letter",
                result.title());
        assertEquals("LLM", result.recovery().finalSource());
        assertEquals(2, result.recovery().providerAttemptCount());
        assertEquals(1, result.recovery().automaticRetryCount());
        assertTrue(result.recovery().retried());
        assertEquals("RATE_LIMITED", result.recovery().retryReason());
        ArgumentCaptor<GenerationRequest> requestCaptor =
                ArgumentCaptor.forClass(GenerationRequest.class);
        verify(llmGatewayApi).generateV2(requestCaptor.capture());
        assertEquals(
                selectedPrompt.getGenerationMetadata().schemaId(),
                requestCaptor.getValue().getOutput().getSchemaId());
        verifyNoInteractions(
                paymentBillingClient,
                documentStoreApi,
                applicationTrackerApi);
    }

    private static Stream<DraftOutputType> selectedOutputs() {
        return Stream.of(
                DraftOutputType.CV,
                DraftOutputType.COVER_LETTER);
    }

    @Test
    void selectedCvReplacesAnUnsupportedNumericSummaryWithProfileBackedSkillProse()
            throws Exception {
        DraftOutputType outputType = DraftOutputType.CV;
        var selectedRequest = validSelectedRequest(outputType);
        selectedRequest.getEvidenceSnapshot().setSectionOrder(
                List.of(EvidenceCategory.OTHER));
        selectedRequest.getEvidenceSnapshot().getSelections().get(0)
                .setCategory(EvidenceCategory.OTHER);
        var selectedInput = new GenerationInputNormalizer()
                .normalizeSelected("user-123", outputType, selectedRequest);
        CvCoverLetterPrompt selectedPrompt = selectedPrompt(
                selectedInput, outputType);
        when(inputNormalizer.normalizeSelected(
                "user-123", outputType, selectedRequest))
                .thenReturn(selectedInput);
        when(promptBuilderService.buildPrompt(selectedInput, outputType))
                .thenReturn(selectedPrompt);
        String unsupported = selectedCvJson().replace(
                "\"personalSummary\": \"Built useful services.\"",
                "\"personalSummary\": \"Built useful services across 12 production releases.\"");
        when(llmGatewayApi.generateV2(any())).thenReturn(
                successfulResponse(unsupported)
                        .schemaId(selectedPrompt.getGenerationMetadata().schemaId())
                        .schemaVersion(
                                selectedPrompt.getGenerationMetadata()
                                        .schemaVersion()));

        SelectedDraftGenerationResponse result = service.generateSelectedDraft(
                "user-123",
                UUID.randomUUID(),
                outputType,
                selectedRequest);

        org.junit.jupiter.api.Assertions.assertTrue(
                result.content().contains(
                        "Profile-backed skills include Java."));
        assertEquals(7300L, result.billableTokens());
        assertFalse(result.recovery().fallbackUsed());
        assertEquals(
                List.of(com.jobseekercopilot.cvcoverletter
                        .GenerationInputFixtures.CV_SKILL_FACT_ID.toString()),
                result.claimLedger().claims().stream()
                        .filter(claim -> claim.contentPaths().equals(
                                List.of("/cv/personalSummary")))
                        .findFirst()
                        .orElseThrow()
                        .evidenceIds());
    }

    @Test
    void selectedCoverLetterRecoversWhenARetryPassesValidation()
            throws Exception {
        DraftOutputType outputType = DraftOutputType.COVER_LETTER;
        CvCoverLetterService retryingService = serviceWithValidationRetries(1);
        var selectedRequest = validSelectedRequest(outputType);
        var selectedInput = new GenerationInputNormalizer()
                .normalizeSelected("user-123", outputType, selectedRequest);
        CvCoverLetterPrompt selectedPrompt = selectedPrompt(
                selectedInput, outputType);
        when(inputNormalizer.normalizeSelected(
                "user-123", outputType, selectedRequest))
                .thenReturn(selectedInput);
        when(promptBuilderService.buildPrompt(selectedInput, outputType))
                .thenReturn(selectedPrompt);
        String rejected = unrepairableCoverLetterJson();
        when(llmGatewayApi.generateV2(any()))
                .thenReturn(selectedResponse(rejected, selectedPrompt))
                .thenReturn(selectedResponse(
                        selectedCoverLetterJson(), selectedPrompt));

        SelectedDraftGenerationResponse result =
                retryingService.generateSelectedDraft(
                        "user-123",
                        UUID.randomUUID(),
                        outputType,
                        selectedRequest);

        assertEquals("Java Developer Cover Letter", result.title());
        assertFalse(result.recovery().fallbackUsed());
        // First attempt rejected, second attempt accepted.
        verify(llmGatewayApi, times(2)).generateV2(any());
    }

    @Test
    void selectedCoverLetterRetryUsesPerturbedTemperatureAndRespectsTheCap()
            throws Exception {
        DraftOutputType outputType = DraftOutputType.COVER_LETTER;
        CvCoverLetterService retryingService = serviceWithValidationRetries(1);
        var selectedRequest = validSelectedRequest(outputType);
        var selectedInput = new GenerationInputNormalizer()
                .normalizeSelected("user-123", outputType, selectedRequest);
        CvCoverLetterPrompt selectedPrompt = selectedPrompt(
                selectedInput, outputType);
        when(inputNormalizer.normalizeSelected(
                "user-123", outputType, selectedRequest))
                .thenReturn(selectedInput);
        when(promptBuilderService.buildPrompt(selectedInput, outputType))
                .thenReturn(selectedPrompt);
        String rejected = unrepairableCoverLetterJson();
        when(llmGatewayApi.generateV2(any()))
                .thenReturn(selectedResponse(rejected, selectedPrompt));

        InvalidLlmResponseException failure = assertThrows(
                InvalidLlmResponseException.class,
                () -> retryingService.generateSelectedDraft(
                        "user-123",
                        UUID.randomUUID(),
                        outputType,
                        selectedRequest));

        assertTrue(failure.getMessage().contains("bounded text policy"));
        // One initial attempt + exactly one retry (cap = 1).
        ArgumentCaptor<GenerationRequest> requestCaptor =
                ArgumentCaptor.forClass(GenerationRequest.class);
        verify(llmGatewayApi, times(2)).generateV2(requestCaptor.capture());
        assertEquals(
                0.0,
                requestCaptor.getAllValues().get(0).getLimits().getTemperature());
        assertEquals(
                0.4,
                requestCaptor.getAllValues().get(1).getLimits().getTemperature());
    }

    @Test
    void selectedCvFallsBackAfterAllValidationRetriesAreExhausted()
            throws Exception {
        DraftOutputType outputType = DraftOutputType.CV;
        CvCoverLetterService retryingService = serviceWithValidationRetries(2);
        var selectedRequest = validSelectedRequest(outputType);
        selectedRequest.getEvidenceSnapshot().setSectionOrder(
                List.of(EvidenceCategory.OTHER));
        selectedRequest.getEvidenceSnapshot().getSelections().get(0)
                .setCategory(EvidenceCategory.OTHER);
        var selectedInput = new GenerationInputNormalizer()
                .normalizeSelected("user-123", outputType, selectedRequest);
        CvCoverLetterPrompt selectedPrompt = selectedPrompt(
                selectedInput, outputType);
        when(inputNormalizer.normalizeSelected(
                "user-123", outputType, selectedRequest))
                .thenReturn(selectedInput);
        when(promptBuilderService.buildPrompt(selectedInput, outputType))
                .thenReturn(selectedPrompt);
        String unsupported = selectedCvJson().replace(
                "80000000-0000-4000-8000-000000000002",
                "90000000-0000-4000-8000-000000000099");
        when(llmGatewayApi.generateV2(any()))
                .thenReturn(selectedResponse(unsupported, selectedPrompt));

        SelectedDraftGenerationResponse result =
                retryingService.generateSelectedDraft(
                        "user-123",
                        UUID.randomUUID(),
                        outputType,
                        selectedRequest);

        assertTrue(result.recovery().fallbackUsed());
        // Initial attempt + two retries all rejected before the fallback.
        verify(llmGatewayApi, times(3)).generateV2(any());
    }

    private CvCoverLetterService serviceWithValidationRetries(int retries) {
        LlmProperties properties = new LlmProperties();
        properties.setTaskType("CV_COVER_LETTER_GENERATION");
        properties.setTemperature(0.0);
        properties.setMaxTokens(2500);
        properties.setMaxValidationRetries(retries);
        properties.setRetryTemperature(0.4);
        return new CvCoverLetterService(
                promptBuilderService,
                inputNormalizer,
                llmGatewayApi,
                paymentBillingClient,
                properties,
                new LlmResponseParser(
                        new ObjectMapper(),
                        new ClaimEvidenceValidator(),
                        new GeneratedDocumentQualityValidator()),
                new CvDocumentRenderer(),
                new CoverLetterDocumentRenderer(),
                new ValidatedClaimLedgerFactory(),
                documentStoreApi,
                applicationTrackerApi,
                quarantineService,
                new DeterministicCvFallbackService(new ObjectMapper()));
    }

    private GenerationResponse selectedResponse(
            String output, CvCoverLetterPrompt selectedPrompt) {
        return successfulResponse(output)
                .schemaId(selectedPrompt.getGenerationMetadata().schemaId())
                .schemaVersion(
                        selectedPrompt.getGenerationMetadata().schemaVersion());
    }

    @Test
    void selectedCvFallbackRecordsContentFreeRejectionCategory()
            throws Exception {
        DraftOutputType outputType = DraftOutputType.CV;
        var selectedRequest = validSelectedRequest(outputType);
        selectedRequest.getEvidenceSnapshot().setSectionOrder(
                List.of(EvidenceCategory.OTHER));
        selectedRequest.getEvidenceSnapshot().getSelections().get(0)
                .setCategory(EvidenceCategory.OTHER);
        var selectedInput = new GenerationInputNormalizer()
                .normalizeSelected("user-123", outputType, selectedRequest);
        CvCoverLetterPrompt selectedPrompt = selectedPrompt(
                selectedInput, outputType);
        when(inputNormalizer.normalizeSelected(
                "user-123", outputType, selectedRequest))
                .thenReturn(selectedInput);
        when(promptBuilderService.buildPrompt(selectedInput, outputType))
                .thenReturn(selectedPrompt);
        String unsupported = selectedCvJson().replace(
                "80000000-0000-4000-8000-000000000002",
                "90000000-0000-4000-8000-000000000099");
        when(llmGatewayApi.generateV2(any())).thenReturn(
                successfulResponse(unsupported)
                        .schemaId(selectedPrompt.getGenerationMetadata().schemaId())
                        .schemaVersion(
                                selectedPrompt.getGenerationMetadata()
                                        .schemaVersion()));
        when(quarantineService.diagnostic(any()))
                .thenReturn(new RejectedGenerationDiagnostic(
                        "CLAIM_EVIDENCE",
                        "$.claims[0].evidenceIds",
                        "claim is absent from approved evidence"));

        SelectedDraftGenerationResponse result = service.generateSelectedDraft(
                "user-123",
                UUID.randomUUID(),
                outputType,
                selectedRequest);

        assertTrue(result.recovery().fallbackUsed());
        assertEquals(
                "MODEL_OUTPUT_REJECTED_CLAIM_EVIDENCE",
                result.recovery().fallbackReason());
        assertEquals(0L, result.billableTokens());
        assertEquals(1, result.recovery().providerAttemptCount());
        assertEquals(0, result.recovery().automaticRetryCount());
        assertFalse(result.recovery().retried());
        assertNull(result.recovery().retryReason());
        verify(llmGatewayApi, times(1)).generateV2(any());
    }

    @Test
    void richFictionalProfileStillProducesBoundedGroundedFallbackAfterProviderFailure()
            throws Exception {
        DraftOutputType outputType = DraftOutputType.CV;
        var selectedRequest = validSelectedRequest(outputType);
        selectedRequest.getProfile().setSkills(
                java.util.stream.IntStream.rangeClosed(1, 40)
                        .mapToObj(index -> "Evidence Skill " + index)
                        .toList());
        selectedRequest.getEvidenceSnapshot().setSectionOrder(List.of(
                EvidenceCategory.EMPLOYMENT,
                EvidenceCategory.QUALIFICATION_TRAINING,
                EvidenceCategory.PROJECT));
        for (int index = 1; index <= 12; index++) {
            selectedRequest.getEvidenceSnapshot().getSelections().add(
                    new com.jobseekercopilot.cvcoverletter.dto.EvidenceSnapshotSelectionInput(
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            1,
                            EvidenceCategory.EMPLOYMENT,
                            "e".repeat(64),
                            List.of(
                                    richFact("ROLE_TITLE", "Software Role " + index),
                                    richFact("ORGANISATION", "Fictional Employer " + index),
                                    richFact("START_DATE", "2020-01"),
                                    richFact("END_DATE", "2025-01"),
                                    richFact("RESPONSIBILITIES",
                                            "Delivered evidence-backed software capability "
                                                    + index + "."))));
        }
        for (int index = 1; index <= 8; index++) {
            selectedRequest.getEvidenceSnapshot().getSelections().add(
                    new com.jobseekercopilot.cvcoverletter.dto.EvidenceSnapshotSelectionInput(
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            1,
                            EvidenceCategory.QUALIFICATION_TRAINING,
                            "f".repeat(64),
                            List.of(
                                    richFact("QUALIFICATION_TITLE",
                                            "Qualification " + index),
                                    richFact("ISSUER", "Fictional Institute"),
                                    richFact("RESULT", "Pass"),
                                    richFact("ISSUE_DATE", "2025-01"))));
        }
        var selectedInput = new GenerationInputNormalizer()
                .normalizeSelected("user-123", outputType, selectedRequest);
        CvCoverLetterPrompt selectedPrompt = selectedPrompt(
                selectedInput, outputType);
        when(inputNormalizer.normalizeSelected(
                "user-123", outputType, selectedRequest))
                .thenReturn(selectedInput);
        when(promptBuilderService.buildPrompt(selectedInput, outputType))
                .thenReturn(selectedPrompt);
        when(llmGatewayApi.generateV2(any()))
                .thenThrow(new org.springframework.web.client.RestClientException(
                        "provider unavailable"));

        SelectedDraftGenerationResponse result =
                service.generateSelectedDraft(
                        "user-123",
                        UUID.randomUUID(),
                        outputType,
                        selectedRequest);

        assertEquals(0L, result.billableTokens());
        assertTrue(result.recovery().fallbackUsed());
        assertEquals("PROVIDER_FAILURE", result.recovery().fallbackReason());
        assertEquals(0, result.recovery().providerAttemptCount());
        assertFalse(result.recovery().retried());
        assertTrue(result.content().contains("Fictional Employer 1"));
        assertTrue(result.content().contains("Evidence Skill 1"));
        assertTrue(result.content().contains("Fictional Employer 12"));
        assertFalse(result.content().contains("Evidence Skill 13"));
        assertTrue(result.claimLedger().claims().size() <= 120);
        verify(llmGatewayApi, times(1)).generateV2(any());
    }

    private com.jobseekercopilot.cvcoverletter.dto.EvidenceSnapshotFactInput
            richFact(String type, String value) {
        return new com.jobseekercopilot.cvcoverletter.dto.EvidenceSnapshotFactInput(
                UUID.randomUUID(), type, value, false);
    }

    @Test
    void malformedDedicatedSchemaFailsBeforeBillingOrProviderInvocation()
            throws Exception {
        when(promptBuilderService.buildPrompt(normalizedInput))
                .thenReturn(prompt(
                        malformedActiveOutputSchema(), promptMetadata()));

        assertThrows(
                IllegalStateException.class,
                () -> service.generate("user-123", request));

        verifyNoInteractions(
                llmGatewayApi,
                paymentBillingClient,
                documentStoreApi,
                applicationTrackerApi);
    }

    @Test
    void malformedDedicatedSchemaFailsBeforeDraftProviderInvocation()
            throws Exception {
        when(promptBuilderService.buildPrompt(normalizedInput))
                .thenReturn(prompt(
                        malformedActiveOutputSchema(), promptMetadata()));

        assertThrows(
                IllegalStateException.class,
                () -> service.generateDraft(
                        "user-123", UUID.randomUUID(), request));

        verifyNoInteractions(
                llmGatewayApi,
                paymentBillingClient,
                documentStoreApi,
                applicationTrackerApi);
    }

    @Test
    void draftLedgerRecordsRollbackParserProvenance() throws Exception {
        when(promptBuilderService.buildPrompt(normalizedInput))
                .thenReturn(prompt(
                        rollbackOutputSchema(), rollbackPromptMetadata()));
        when(llmGatewayApi.generateV2(any())).thenReturn(
                successfulResponse(validJson()).schemaVersion("3.4.0"));

        DraftGenerationResponse result = service.generateDraft(
                "user-123", UUID.randomUUID(), request);

        assertEquals("3.2.0", result.claimLedger().parserVersion());
        assertEquals("2.10.0", result.claimLedger().policyVersion());
        assertEquals(10, result.claimLedger().claims().size());
        verifyNoInteractions(
                paymentBillingClient,
                documentStoreApi,
                applicationTrackerApi);
    }

    @Test
    void draftRejectsNonEmptyCoreSkillEvidenceAfterOneProviderCall()
            throws Exception {
        UUID operationId = UUID.randomUUID();
        when(quarantineService.isEnabled()).thenReturn(true);
        when(llmGatewayApi.generateV2(any())).thenReturn(
                successfulResponse(activeJsonWithNonEmptyCoreSkillEvidence()));

        assertThrows(
                InvalidLlmResponseException.class,
                () -> service.generateDraft(
                        "user-123", operationId, request));

        verify(llmGatewayApi).generateV2(any());
        ArgumentCaptor<RejectedGenerationCaptureContext> capture =
                ArgumentCaptor.forClass(RejectedGenerationCaptureContext.class);
        verify(quarantineService).capture(capture.capture());
        assertEquals(operationId, capture.getValue().operationId());
        assertEquals("user-123", capture.getValue().ownerId());
        assertEquals(
                activeJsonWithNonEmptyCoreSkillEvidence(),
                capture.getValue().generationResponse().getOutput());
        verifyNoInteractions(
                paymentBillingClient,
                documentStoreApi,
                applicationTrackerApi);
    }

    @Test
    void quarantineStorageFailureNeverTriggersAnotherProviderCall() {
        UUID operationId = UUID.randomUUID();
        when(quarantineService.isEnabled()).thenReturn(true);
        doThrow(new RejectedGenerationQuarantineException("disk unavailable"))
                .when(quarantineService).capture(any());
        when(llmGatewayApi.generateV2(any())).thenReturn(
                successfulResponse(activeJsonWithNonEmptyCoreSkillEvidence()));

        InvalidLlmResponseException rejection = assertThrows(
                InvalidLlmResponseException.class,
                () -> service.generateDraft("user-123", operationId, request));

        assertEquals(1, rejection.getSuppressed().length);
        verify(llmGatewayApi, org.mockito.Mockito.times(1)).generateV2(any());
        verifyNoInteractions(
                paymentBillingClient,
                documentStoreApi,
                applicationTrackerApi);
    }

    @Test
    void acceptedReplayReturnsTheRecoveredDraftWithoutCallingAnyProviderOrOwnerSideEffect()
            throws Exception {
        UUID operationId = UUID.randomUUID();
        GenerationResponse captured = successfulResponse(activeValidJson());
        RejectedGenerationArtifact artifact = rejectedArtifact(operationId);
        when(quarantineService.load("user-123", operationId)).thenReturn(
                new RejectedGenerationQuarantineService.LoadedRejectedGeneration(
                        artifact,
                        captured));
        when(promptBuilderService.buildPrompt(
                normalizedInput,
                artifact.promptReleaseId())).thenReturn(
                prompt(activeOutputSchema(), promptMetadata()));
        RejectedGenerationReplayAuditEvent replayEvent = replayEvent(
                2,
                "ACCEPTED",
                null);
        when(quarantineService.recordReplay(
                any(), any(), any(), any(), any(), any(), isNull()))
                .thenReturn(replayEvent);

        RejectedGenerationReplayResponse result =
                service.replayRejectedDraft("user-123", operationId, request);

        assertEquals("ACCEPTED", result.outcome());
        assertEquals(0, result.providerInvocationCount());
        assertEquals("Java Developer CV", result.draft().cvTitle());
        assertEquals(7300L, result.draft().usage().totalTokens());
        verify(quarantineService).requireMatchingReplayContext(
                any(), any(), any());
        verifyNoInteractions(
                llmGatewayApi,
                paymentBillingClient,
                documentStoreApi,
                applicationTrackerApi);
    }

    @Test
    void rejectedReplayReturnsStructuredDiagnosticsAndStillMakesNoProviderCall()
            throws Exception {
        UUID operationId = UUID.randomUUID();
        GenerationResponse captured = successfulResponse(
                activeJsonWithNonEmptyCoreSkillEvidence());
        RejectedGenerationArtifact artifact = rejectedArtifact(operationId);
        when(quarantineService.load("user-123", operationId)).thenReturn(
                new RejectedGenerationQuarantineService.LoadedRejectedGeneration(
                        artifact,
                        captured));
        when(promptBuilderService.buildPrompt(
                normalizedInput,
                artifact.promptReleaseId())).thenReturn(
                prompt(activeOutputSchema(), promptMetadata()));
        when(quarantineService.diagnostic(any())).thenReturn(
                new com.jobseekercopilot.cvcoverletter.quarantine.RejectedGenerationDiagnostic(
                        "OUTPUT_VALIDATION",
                        "$.cv.coreSkills[0].evidence",
                        "Expected an empty value."));
        when(quarantineService.recordReplay(
                any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(replayEvent(2, "REJECTED", null));

        RejectedGenerationReplayResponse result =
                service.replayRejectedDraft("user-123", operationId, request);

        assertEquals("REJECTED", result.outcome());
        assertEquals(0, result.providerInvocationCount());
        assertNull(result.draft());
        assertEquals(
                "$.cv.coreSkills[0].evidence",
                result.diagnostic().path());
        verifyNoInteractions(
                llmGatewayApi,
                paymentBillingClient,
                documentStoreApi,
                applicationTrackerApi);
    }

    @Test
    void acceptedSelectedReplayUsesTheOriginalOutputContractWithoutCallingAnyProvider()
            throws Exception {
        UUID operationId = UUID.randomUUID();
        DraftOutputType outputType = DraftOutputType.COVER_LETTER;
        var selectedRequest = validSelectedRequest(outputType);
        var selectedInput = new GenerationInputNormalizer()
                .normalizeSelected("user-123", outputType, selectedRequest);
        CvCoverLetterPrompt selectedPrompt = selectedPrompt(
                selectedInput, outputType);
        GenerationResponse captured = successfulResponse(
                        selectedCoverLetterJson())
                .schemaId(selectedPrompt.getGenerationMetadata().schemaId())
                .schemaVersion(
                        selectedPrompt.getGenerationMetadata().schemaVersion());
        RejectedGenerationArtifact artifact = rejectedArtifact(operationId);
        when(quarantineService.load("user-123", operationId)).thenReturn(
                new RejectedGenerationQuarantineService.LoadedRejectedGeneration(
                        artifact,
                        captured));
        when(inputNormalizer.normalizeSelected(
                "user-123", outputType, selectedRequest))
                .thenReturn(selectedInput);
        when(promptBuilderService.buildPrompt(
                selectedInput,
                artifact.promptReleaseId(),
                outputType)).thenReturn(selectedPrompt);
        when(quarantineService.recordReplay(
                any(), any(), any(), any(), any(), any(), isNull()))
                .thenReturn(replayEvent(2, "ACCEPTED", null));

        RejectedSelectedGenerationReplayResponse result =
                service.replayRejectedSelectedDraft(
                        "user-123",
                        operationId,
                        outputType,
                        selectedRequest);

        assertEquals("ACCEPTED", result.outcome());
        assertEquals(0, result.providerInvocationCount());
        assertEquals(outputType, result.draft().outputType());
        assertEquals(
                "Java Developer Cover Letter",
                result.draft().title());
        verify(quarantineService).requireMatchingReplayContext(
                any(), any(), any());
        verifyNoInteractions(
                llmGatewayApi,
                paymentBillingClient,
                documentStoreApi,
                applicationTrackerApi);
    }

    @Test
    void draftProjectsSupportedSkillsAfterOneProviderCallWithoutSideEffects()
            throws Exception {
        UUID operationId = UUID.randomUUID();
        when(llmGatewayApi.generateV2(any())).thenReturn(
                successfulResponse(activeJsonWithNoisyCoreSkills()));

        DraftGenerationResponse actual = service.generateDraft(
                "user-123",
                operationId,
                request);

        org.junit.jupiter.api.Assertions.assertTrue(
                actual.cvContent().contains(
                        "Key Skills\nSpring, Java"));
        assertEquals(
                java.util.Set.of(
                        "/cv/coreSkills/0/name",
                        "/cv/coreSkills/1/name"),
                actual.claimLedger().claims().stream()
                        .flatMap(claim -> claim.contentPaths().stream())
                        .filter(path -> path.startsWith("/cv/coreSkills/"))
                        .collect(java.util.stream.Collectors.toSet()));
        verify(llmGatewayApi, org.mockito.Mockito.times(1))
                .generateV2(any());
        verifyNoInteractions(
                paymentBillingClient,
                documentStoreApi,
                applicationTrackerApi);
    }

    @Test
    void rejectsQualificationTitlePathAfterOneProviderCallAndPersistsNothing()
            throws Exception {
        UUID reservationId = UUID.randomUUID();
        when(paymentBillingClient.reserve(any(), any())).thenReturn(
                new PaymentBillingClient.ReservationResponse(
                        reservationId,
                        "user-123",
                        5000,
                        40000,
                        "RESERVED"));
        String invalidPath = activeValidJson().replace(
                "/cv/targetRole",
                "/cv/qualifications/0/qualificationTitle");
        when(llmGatewayApi.generateV2(any())).thenReturn(
                successfulResponse(invalidPath));

        InvalidLlmResponseException failure = assertThrows(
                InvalidLlmResponseException.class,
                () -> service.generate("user-123", request));

        org.junit.jupiter.api.Assertions.assertTrue(
                failure.getMessage().contains(
                        "$.claims[0].contentPaths[0]"));
        verify(llmGatewayApi, org.mockito.Mockito.times(1))
                .generateV2(any());
        verify(paymentBillingClient).release(
                "user-123",
                reservationId,
                "Document generation failed");
        verify(paymentBillingClient, never()).commit(any(), any(), any());
        verifyNoInteractions(documentStoreApi, applicationTrackerApi);
    }

    @Test
    void generatesStoresBothDocumentsCreatesApplicationAndReturnsContent(CapturedOutput output) {
        when(llmGatewayApi.generateV2(any())).thenReturn(successfulResponse(
                activeValidJson().replace("Focused on Java.", "response-secret-sentinel")));
        UUID cvId = UUID.randomUUID();
        UUID letterId = UUID.randomUUID();
        UUID applicationId = UUID.randomUUID();
        when(documentStoreApi.createDocument(any(), any())).thenReturn(
                new GeneratedDocumentResponse().id(cvId),
                new GeneratedDocumentResponse().id(letterId));
        when(applicationTrackerApi.createApplication(any()))
                .thenReturn(new ApplicationRecordResponse().id(applicationId));

        GenerateCvCoverLetterResponse actual = service.generate("user-123", request);

        ArgumentCaptor<GenerationRequest> llmCaptor = ArgumentCaptor.forClass(GenerationRequest.class);
        verify(llmGatewayApi).generateV2(llmCaptor.capture());
        GenerationRequest llmRequest = llmCaptor.getValue();
        assertEquals(GenerationRequest.ContractVersionEnum._2_0, llmRequest.getContractVersion());
        assertEquals("CV_COVER_LETTER_GENERATION", llmRequest.getTask());
        assertEquals("trusted generation rules", llmRequest.getTrustedInstructions());
        assertEquals("{\"job\":\"input-secret-sentinel\"}", llmRequest.getUntrustedInput());
        assertEquals("cv-cover-letter-output", llmRequest.getOutput().getSchemaId());
        assertEquals("3.8.0", llmRequest.getOutput().getSchemaVersion());
        assertEquals(0.25, llmRequest.getLimits().getTemperature());
        assertEquals(2500, llmRequest.getLimits().getMaxOutputTokens());

        ArgumentCaptor<com.jobseekercopilot.generated.documentstoreservice.model.CreateDocumentRequest> documentCaptor =
                ArgumentCaptor.forClass(com.jobseekercopilot.generated.documentstoreservice.model.CreateDocumentRequest.class);
        verify(documentStoreApi, org.mockito.Mockito.times(2))
                .createDocument(documentCaptor.capture(), org.mockito.Mockito.eq("user-123"));
        assertEquals("CV", documentCaptor.getAllValues().get(0).getDocumentType().getValue());
        assertEquals("COVER_LETTER", documentCaptor.getAllValues().get(1).getDocumentType().getValue());
        assertEquals("user-123", documentCaptor.getAllValues().get(0).getUserId());
        assertEquals("user-123", documentCaptor.getAllValues().get(1).getUserId());

        ArgumentCaptor<CreateApplicationRequest> applicationCaptor = ArgumentCaptor.forClass(CreateApplicationRequest.class);
        verify(applicationTrackerApi).createApplication(applicationCaptor.capture());
        assertEquals("user-123", applicationCaptor.getValue().getUserId());
        assertEquals(cvId.toString(), applicationCaptor.getValue().getCvDocumentId());
        assertEquals(letterId.toString(), applicationCaptor.getValue().getCoverLetterDocumentId());
        assertEquals("Example Ltd", applicationCaptor.getValue().getCompanyName());

        assertEquals(applicationId.toString(), actual.getApplicationId());
        assertEquals(cvId.toString(), actual.getCvDocumentId());
        assertEquals("Java Developer CV", actual.getCvTitle());
        assertEquals("Java Developer CV", actual.getCvContent().lines().findFirst().orElseThrow());
        assertEquals("1.0", actual.getInputSchemaVersion());
        assertEquals("cv-cover-letter-1.5.9", actual.getGenerationMetadata().releaseId());
        assertEquals("1.5.9", actual.getGenerationMetadata().rulesVersion());
        assertEquals("3.8.0", actual.getGenerationMetadata().schemaVersion());
        assertEquals(normalizedInput.warnings(), actual.getInputWarnings());
        org.junit.jupiter.api.Assertions.assertTrue(
                actual.getCoverLetterContent().contains("Dear Hiring Manager,"));

        ArgumentCaptor<PaymentBillingClient.ReservationRequest> reservationCaptor =
                ArgumentCaptor.forClass(PaymentBillingClient.ReservationRequest.class);
        verify(paymentBillingClient).reserve(org.mockito.Mockito.eq("user-123"), reservationCaptor.capture());
        assertEquals("CV_AND_COVER_LETTER_GENERATION", reservationCaptor.getValue().feature());
        assertEquals(5000L, reservationCaptor.getValue().estimatedTokens());
        org.junit.jupiter.api.Assertions.assertTrue(
                reservationCaptor.getValue().operationKey().matches(
                        "cv-generation:[0-9a-f-]{36}"));

        ArgumentCaptor<PaymentBillingClient.CommitReservationRequest> commitCaptor =
                ArgumentCaptor.forClass(PaymentBillingClient.CommitReservationRequest.class);
        verify(paymentBillingClient).commit(org.mockito.Mockito.eq("user-123"), any(), commitCaptor.capture());
        assertEquals(7300L, commitCaptor.getValue().actualTokens());
        assertNull(commitCaptor.getValue().provider());
        assertEquals("gpt-4.1-mini-2025-04-14", commitCaptor.getValue().model());
        org.junit.jupiter.api.Assertions.assertTrue(
                output.getAll().contains("modelId=gpt-4.1-mini-2025-04-14"));
        org.junit.jupiter.api.Assertions.assertTrue(
                output.getAll().contains("modelDeploymentVersion=document-generation-model-2026-07"));
        org.junit.jupiter.api.Assertions.assertTrue(
                output.getAll().contains("admissionPolicyVersion=document-generation-admission-2026-07"));
        org.junit.jupiter.api.Assertions.assertTrue(
                output.getAll().contains("pricingVersion=openai-standard-2026-07-25"));
        org.junit.jupiter.api.Assertions.assertTrue(
                output.getAll().contains("promptRelease=cv-cover-letter-1.5.9"));
        org.junit.jupiter.api.Assertions.assertTrue(
                output.getAll().contains("templateVersion=1.2.0"));
        org.junit.jupiter.api.Assertions.assertTrue(
                output.getAll().contains("rulesVersion=1.5.9"));
        org.junit.jupiter.api.Assertions.assertTrue(
                output.getAll().contains("schemaVersion=3.8.0"));
        org.junit.jupiter.api.Assertions.assertTrue(
                output.getAll().contains("parserVersion=3.5.2"));
        assertFalse(output.getAll().contains("input-secret-sentinel"));
        assertFalse(output.getAll().contains("response-secret-sentinel"));
    }

    @Test
    void doesNotCreateApplicationWhenSecondDocumentSaveFails() {
        UUID reservationId = UUID.randomUUID();
        when(paymentBillingClient.reserve(any(), any())).thenReturn(
                new PaymentBillingClient.ReservationResponse(reservationId, "user-123", 5000, 40000, "RESERVED"));
        when(llmGatewayApi.generateV2(any())).thenReturn(successfulResponse(activeValidJson()));
        when(documentStoreApi.createDocument(any(), any()))
                .thenReturn(new GeneratedDocumentResponse().id(UUID.randomUUID()))
                .thenThrow(new RestClientException("down"));

        assertThrows(DownstreamServiceException.class, () -> service.generate("user-123", request));

        verify(documentStoreApi, org.mockito.Mockito.times(2))
                .createDocument(any(), org.mockito.Mockito.eq("user-123"));
        verifyNoInteractions(applicationTrackerApi);
        verify(paymentBillingClient, never()).commit(any(), any(), any());
        verify(paymentBillingClient).release("user-123", reservationId, "Document generation failed");
    }

    @Test
    void releasesReservationAndDoesNotCommitWhenTrackerDeniesProducer() {
        UUID reservationId = UUID.randomUUID();
        when(paymentBillingClient.reserve(any(), any())).thenReturn(
                new PaymentBillingClient.ReservationResponse(
                        reservationId, "user-123", 5000, 40000, "RESERVED"));
        when(llmGatewayApi.generateV2(any())).thenReturn(successfulResponse(activeValidJson()));
        when(documentStoreApi.createDocument(any(), any())).thenReturn(
                new GeneratedDocumentResponse().id(UUID.randomUUID()),
                new GeneratedDocumentResponse().id(UUID.randomUUID()));
        when(applicationTrackerApi.createApplication(any()))
                .thenThrow(new RestClientException("producer denied"));

        DownstreamServiceException failure = assertThrows(
                DownstreamServiceException.class,
                () -> service.generate("user-123", request));

        assertEquals("Application tracker is unavailable", failure.getMessage());
        verify(paymentBillingClient, never()).commit(any(), any(), any());
        verify(paymentBillingClient).release(
                "user-123", reservationId, "Document generation failed");
    }

    @ParameterizedTest
    @MethodSource("invalidModelOutputs")
    void releasesReservationAndPersistsNothingWhenModelOutputIsUnsafe(String unsafeOutput) {
        UUID reservationId = UUID.randomUUID();
        when(paymentBillingClient.reserve(any(), any())).thenReturn(
                new PaymentBillingClient.ReservationResponse(reservationId, "user-123", 5000, 40000, "RESERVED"));
        when(llmGatewayApi.generateV2(any())).thenReturn(successfulResponse(unsafeOutput));

        assertThrows(InvalidLlmResponseException.class, () -> service.generate("user-123", request));

        verify(llmGatewayApi).generateV2(any());
        verify(paymentBillingClient, never()).commit(any(), any(), any());
        verify(paymentBillingClient).release("user-123", reservationId, "Document generation failed");
        verifyNoInteractions(documentStoreApi, applicationTrackerApi);
    }

    @ParameterizedTest
    @MethodSource("invalidGenerationAudits")
    void invalidGenerationAuditFailsBeforePersistenceAndBillingCommit(GenerationAudit invalidAudit) {
        UUID reservationId = UUID.randomUUID();
        when(paymentBillingClient.reserve(any(), any())).thenReturn(
                new PaymentBillingClient.ReservationResponse(
                        reservationId, "user-123", 5000, 40000, "RESERVED"));
        when(llmGatewayApi.generateV2(any())).thenReturn(
                successfulResponse(activeValidJson()).audit(invalidAudit));

        assertThrows(
                InvalidLlmResponseException.class,
                () -> service.generate("user-123", request));

        verify(paymentBillingClient, never()).commit(any(), any(), any());
        verify(paymentBillingClient).release(
                "user-123", reservationId, "Document generation failed");
        verifyNoInteractions(documentStoreApi, applicationTrackerApi);
    }

    @Test
    void releasesReservationWhenLlmGatewayFails() {
        UUID reservationId = UUID.randomUUID();
        when(paymentBillingClient.reserve(any(), any())).thenReturn(
                new PaymentBillingClient.ReservationResponse(reservationId, "user-123", 5000, 40000, "RESERVED"));
        when(llmGatewayApi.generateV2(any())).thenThrow(new RestClientException("down"));

        assertThrows(DownstreamServiceException.class, () -> service.generate("user-123", request));

        verify(paymentBillingClient).release("user-123", reservationId, "LLM generation failed");
        verifyNoInteractions(documentStoreApi, applicationTrackerApi);
    }

    @Test
    void unresolvedBillingCompensationIsSurfacedInsteadOfSwallowed() {
        UUID reservationId = UUID.randomUUID();
        when(paymentBillingClient.reserve(any(), any())).thenReturn(
                new PaymentBillingClient.ReservationResponse(
                        reservationId, "user-123", 5000, 40000, "RESERVED"));
        when(llmGatewayApi.generateV2(any())).thenThrow(new RestClientException("llm down"));
        DownstreamServiceException compensationFailure =
                new DownstreamServiceException("Payment service release failed", null);
        org.mockito.Mockito.doThrow(compensationFailure)
                .when(paymentBillingClient)
                .release("user-123", reservationId, "LLM generation failed");

        DownstreamServiceException failure = assertThrows(
                DownstreamServiceException.class,
                () -> service.generate("user-123", request));

        assertEquals(
                "Document generation failed and billing compensation is unresolved",
                failure.getMessage());
        assertEquals(compensationFailure, failure.getCause());
        assertEquals(1, failure.getSuppressed().length);
        assertEquals("LLM gateway is unavailable", failure.getSuppressed()[0].getMessage());
        verifyNoInteractions(documentStoreApi, applicationTrackerApi);
    }

    @Test
    void filteredGenerationFailsSafelyAndReleasesReservation() {
        UUID reservationId = UUID.randomUUID();
        when(paymentBillingClient.reserve(any(), any())).thenReturn(
                new PaymentBillingClient.ReservationResponse(
                        reservationId, "user-123", 5000, 40000, "RESERVED"));
        when(llmGatewayApi.generateV2(any())).thenReturn(successfulResponse(activeValidJson())
                .finishReason(GenerationResponse.FinishReasonEnum.FILTERED));

        assertThrows(InvalidLlmResponseException.class, () -> service.generate("user-123", request));

        verify(paymentBillingClient).release("user-123", reservationId, "Document generation failed");
        verify(paymentBillingClient, never()).commit(any(), any(), any());
        verifyNoInteractions(documentStoreApi, applicationTrackerApi);
    }

    @Test
    void mismatchedOutputSchemaFailsSafelyAndReleasesReservation() {
        UUID reservationId = UUID.randomUUID();
        when(paymentBillingClient.reserve(any(), any())).thenReturn(
                new PaymentBillingClient.ReservationResponse(
                        reservationId, "user-123", 5000, 40000, "RESERVED"));
        when(llmGatewayApi.generateV2(any())).thenReturn(successfulResponse(activeValidJson())
                .schemaVersion("unexpected"));

        assertThrows(InvalidLlmResponseException.class, () -> service.generate("user-123", request));

        verify(paymentBillingClient).release("user-123", reservationId, "Document generation failed");
        verify(paymentBillingClient, never()).commit(any(), any(), any());
        verifyNoInteractions(documentStoreApi, applicationTrackerApi);
    }

    private GenerationResponse successfulResponse(String output) {
        return new GenerationResponse()
                .contractVersion("2.0")
                .output(output)
                .finishReason(GenerationResponse.FinishReasonEnum.COMPLETED)
                .schemaId("cv-cover-letter-output")
                .schemaVersion("3.8.0")
                .audit(generationAudit())
                .usage(usage());
    }

    private RejectedGenerationArtifact rejectedArtifact(UUID operationId) {
        return new RejectedGenerationArtifact(
                1,
                operationId,
                "user-123",
                Instant.parse("2026-08-07T20:00:00Z"),
                Instant.parse("2026-08-08T20:00:00Z"),
                "cv-cover-letter-1.5.9",
                "a".repeat(64),
                "cv-cover-letter-output",
                "3.8.0",
                "d".repeat(64),
                "1.5.7",
                "e".repeat(64),
                "3.5.2",
                "2.13.0",
                "f".repeat(64),
                "0".repeat(64),
                "{}",
                "gpt-4.1-mini-2025-04-14",
                4200L,
                3100L,
                7300L,
                List.of(replayEvent(1, "REJECTED", null)));
    }

    private RejectedGenerationReplayAuditEvent replayEvent(
            long sequence,
            String outcome,
            com.jobseekercopilot.cvcoverletter.quarantine.RejectedGenerationDiagnostic diagnostic) {
        return new RejectedGenerationReplayAuditEvent(
                sequence,
                Instant.parse("2026-08-07T20:00:00Z"),
                sequence == 1 ? "CAPTURED" : "REPLAYED",
                outcome,
                "a".repeat(64),
                diagnostic,
                sequence == 1 ? null : "b".repeat(64),
                "c".repeat(64));
    }

    private GenerationUsage usage() {
        return new GenerationUsage()
                .inputTokens(4200L)
                .outputTokens(3100L)
                .totalTokens(7300L);
    }

    private static Stream<String> invalidModelOutputs() {
        return Stream.of(
                "not json",
                activeValidJson().replace(
                        "\"cv\": {",
                        "\"unexpected\":\"response-secret-sentinel\",\"cv\": {"),
                activeValidJson().replace(
                        "Tailored Developer CV",
                        "x".repeat(201)),
                activeValidJson().replace(
                        "A Java developer focused on useful services.",
                        "<script>response-secret-sentinel</script>"),
                activeValidJson().replace(
                        "Please consider my application for this role.",
                        "I am keen to apply for this role."),
                activeJsonWithNonEmptyCoreSkillEvidence(),
                activeValidJson().replace("\"JOB.TITLE\"", "\"JOB.UNKNOWN\""),
                "```json\n" + activeValidJson() + "\n```");
    }

    private static String activeJsonWithNonEmptyCoreSkillEvidence() {
        return activeValidJson().replace(
                "\"coreSkills\": []",
                "\"coreSkills\": [{\"name\":\"Java\","
                        + "\"evidence\":\"Built useful Java services.\"}]");
    }

    private static String activeJsonWithNoisyCoreSkills() {
        return activeValidJson().replace(
                "\"coreSkills\": []",
                "\"coreSkills\": ["
                        + "{\"name\":\"Spring\",\"evidence\":\"\"},"
                        + "{\"name\":\"Kubernetes\",\"evidence\":\"\"},"
                        + "{\"name\":\"Spring\",\"evidence\":\"\"}]");
    }

    private static Stream<GenerationAudit> invalidGenerationAudits() {
        return Stream.<GenerationAudit>of(
                null,
                generationAudit().modelId("model with whitespace"),
                generationAudit().modelDeploymentVersion(" "),
                generationAudit().admissionPolicyVersion(" "),
                generationAudit().pricingVersion(" "),
                generationAudit().estimatedInputTokensAtAdmission(-1L),
                generationAudit().estimatedCostMicroUsd(-1L),
                generationAudit().currency("EUR"),
                generationAudit().providerAttemptCount(0),
                generationAudit().providerAttemptCount(3),
                generationAudit().automaticRetryCount(2),
                generationAudit().providerAttemptCount(2)
                        .automaticRetryCount(0),
                generationAudit().retryReason(
                        "private-provider-error-sentinel"));
    }

    private static GenerationAudit generationAudit() {
        return new GenerationAudit()
                .modelId("gpt-4.1-mini-2025-04-14")
                .modelDeploymentVersion("document-generation-model-2026-07")
                .admissionPolicyVersion("document-generation-admission-2026-07")
                .pricingVersion("openai-standard-2026-07-25")
                .estimatedInputTokensAtAdmission(5_372L)
                .estimatedCostMicroUsd(41_400L)
                .currency("USD")
                .providerAttemptCount(1)
                .automaticRetryCount(0);
    }

    private PromptGenerationMetadata promptMetadata() {
        return new PromptGenerationMetadata(
                "cv-cover-letter-1.5.9",
                "cv-cover-letter",
                "1.5.9",
                "a".repeat(64),
                "1.2.0",
                "b".repeat(64),
                "1.5.9",
                "c".repeat(64),
                "cv-cover-letter-output",
                "3.8.0",
                "d".repeat(64),
                "1.5.7",
                "e".repeat(64)
        );
    }

    private PromptGenerationMetadata rollbackPromptMetadata() {
        return new PromptGenerationMetadata(
                "cv-cover-letter-1.5.3",
                "cv-cover-letter",
                "1.5.3",
                "a".repeat(64),
                "1.2.0",
                "b".repeat(64),
                "1.5.3",
                "c".repeat(64),
                "cv-cover-letter-output",
                "3.4.0",
                "d".repeat(64),
                "1.5.1",
                "e".repeat(64)
        );
    }

    private CvCoverLetterPrompt prompt(
            JsonNode outputSchema,
            PromptGenerationMetadata metadata
    ) {
        return CvCoverLetterPrompt.builder()
                .taskType("CV_COVER_LETTER_GENERATION")
                .trustedInstructions("trusted generation rules")
                .untrustedInput("{\"job\":\"input-secret-sentinel\"}")
                .outputSchema(outputSchema)
                .generationMetadata(metadata)
                .evidenceCatalog(
                        new ClaimEvidenceCatalogFactory().create(
                                normalizedInput))
                .build();
    }

    private CvCoverLetterPrompt selectedPrompt(
            NormalizedGenerationInput input,
            DraftOutputType outputType) {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        PromptBundleProperties properties = new PromptBundleProperties();
        properties.setSelectedReleaseId("cv-cover-letter-1.6.0");
        PromptBundleRegistry registry = new PromptBundleRegistry(
                objectMapper,
                new DefaultResourceLoader(),
                properties);
        registry.initialize();
        return new PromptBuilderService(
                objectMapper,
                registry,
                new LlmProperties(),
                new ClaimEvidenceCatalogFactory())
                .buildPrompt(input, outputType);
    }

    static String selectedCvJson() {
        return """
                {
                  "cv": {
                    "title": "Java Developer CV",
                    "targetRole": "Java Developer",
                    "personalSummary": "Built useful services.",
                    "coreSkills": [],
                    "projects": [],
                    "qualifications": [],
                    "workHistory": []
                  },
                  "generationNotes": {
                    "assumptionsMade": [],
                    "missingInformation": [],
                    "tailoringSummary": "Focused on the supplied role."
                  },
                  "claims": [
                    {
                      "claimId": "CLAIM-001",
                      "disposition": "SUPPORTED",
                      "evidenceIds": ["JOB.TITLE"],
                      "contentPaths": ["/cv/targetRole"],
                      "reviewText": ""
                    }
                  ],
                  "personalSummaryClaim": {
                    "claimId": "CLAIM-9003",
                    "disposition": "SUPPORTED",
                    "evidenceIds": ["80000000-0000-4000-8000-000000000002"],
                    "contentPath": "/cv/personalSummary",
                    "reviewText": ""
                  }
                }
                """;
    }

    /**
     * A cover letter the parser cannot repair, for exercising retry mechanics.
     *
     * <p>These tests used to corrupt an ordinary claim's content path. That is
     * no longer a rejection: the parser prunes ordinary-claim pointers the
     * reviewed schema forbids, because one such pointer was discarding entire
     * cover letters in production. A canonical claim's content path is fixed by
     * the schema and never pruned, so it still fails the bounded text policy and
     * still reaches the retry path these tests are about.
     */
    static String unrepairableCoverLetterJson() {
        return selectedCoverLetterJson().replace(
                "\"contentPath\": \"/coverLetter/openingParagraph\"",
                "\"contentPath\": \"/coverLetter/personalSummary\"");
    }

    static String selectedCoverLetterJson() {
        return """
                {
                  "coverLetter": {
                    "title": "Java Developer Cover Letter",
                    "jobTitle": "Java Developer",
                    "companyName": "Example Ltd",
                    "greeting": "Dear Hiring Manager",
                    "openingParagraph": "Please consider my application for this role.",
                    "bodyParagraphs": [
                      {
                        "text": "My experience includes building useful services.",
                        "disposition": "SUPPORTED",
                        "evidenceIds": ["80000000-0000-4000-8000-000000000003"]
                      },
                      {
                        "text": "The role calls for useful and reliable services.",
                        "disposition": "REWORDED",
                        "evidenceIds": ["80000000-0000-4000-8000-000000000003", "JOB.DESCRIPTION"]
                      },
                      {
                        "text": "I would apply this experience to the role.",
                        "disposition": "REWORDED",
                        "evidenceIds": ["80000000-0000-4000-8000-000000000003"]
                      },
                      {
                        "text": "This background would support reliable delivery.",
                        "disposition": "REWORDED",
                        "evidenceIds": ["80000000-0000-4000-8000-000000000003"]
                      }
                    ],
                    "closingParagraph": "Thank you for considering my application.",
                    "signOff": "Yours faithfully"
                  },
                  "generationNotes": {
                    "assumptionsMade": [],
                    "missingInformation": [],
                    "tailoringSummary": "Focused on the supplied role."
                  },
                  "claims": [
                    {
                      "claimId": "CLAIM-003",
                      "disposition": "SUPPORTED",
                      "evidenceIds": ["JOB.TITLE"],
                      "contentPaths": ["/coverLetter/title"],
                      "reviewText": ""
                    },
                    {
                      "claimId": "CLAIM-001",
                      "disposition": "SUPPORTED",
                      "evidenceIds": ["JOB.TITLE"],
                      "contentPaths": ["/coverLetter/jobTitle"],
                      "reviewText": ""
                    },
                    {
                      "claimId": "CLAIM-002",
                      "disposition": "SUPPORTED",
                      "evidenceIds": ["JOB.COMPANY"],
                      "contentPaths": ["/coverLetter/companyName"],
                      "reviewText": ""
                    }
                  ],
                  "canonicalApplicationClaims": {
                    "opening": {
                      "claimId": "CLAIM-9001",
                      "disposition": "SUPPORTED",
                      "generationIntentEvidenceId": "REQUEST.GENERATION_INTENT",
                      "jobTitleEvidenceId": "JOB.TITLE",
                      "companyEvidenceId": "JOB.COMPANY",
                      "contentPath": "/coverLetter/openingParagraph",
                      "reviewText": ""
                    },
                    "closing": {
                      "claimId": "CLAIM-9002",
                      "disposition": "SUPPORTED",
                      "generationIntentEvidenceId": "REQUEST.GENERATION_INTENT",
                      "jobTitleEvidenceId": "JOB.TITLE",
                      "companyEvidenceId": "JOB.COMPANY",
                      "contentPath": "/coverLetter/closingParagraph",
                      "reviewText": ""
                    }
                  }
                }
                """;
    }

    static String validJson() {
        return """
                {
                  "cv": {
                    "title": "Tailored Developer CV",
                    "targetRole": "Java Developer",
                    "personalSummary": "A Java developer focused on useful services.",
                    "coreSkills": [],
                    "projects": [],
                    "qualifications": [],
                    "workHistory": []
                  },
                  "coverLetter": {
                    "title": "Developer Cover Letter",
                    "jobTitle": "Java Developer",
                    "companyName": "Example Ltd",
                    "greeting": "Dear Hiring Manager",
                    "openingParagraph": "Please consider my application for this role.",
                    "bodyParagraphs": ["My experience is a strong match.", "I build useful services.", "The role calls for useful services."],
                    "closingParagraph": "Thank you for considering my application.",
                    "signOff": "Yours faithfully"
                  },
                  "generationNotes": {"assumptionsMade":[],"missingInformation":[],"tailoringSummary":"Focused on Java."},
                  "claims": [
                    {"claimId":"CLAIM-001","disposition":"SUPPORTED","evidenceIds":["JOB.TITLE"],"contentPaths":["/cv/targetRole"],"reviewText":""},
                    {"claimId":"CLAIM-002","disposition":"REWORDED","evidenceIds":["PROFILE.SKILL.1","JOB.DESCRIPTION"],"contentPaths":["/cv/personalSummary"],"reviewText":""},
                    {"claimId":"CLAIM-003","disposition":"REWORDED","evidenceIds":["JOB.TITLE"],"contentPaths":["/coverLetter/title"],"reviewText":""},
                    {"claimId":"CLAIM-004","disposition":"SUPPORTED","evidenceIds":["JOB.TITLE"],"contentPaths":["/coverLetter/jobTitle"],"reviewText":""},
                    {"claimId":"CLAIM-005","disposition":"SUPPORTED","evidenceIds":["JOB.COMPANY"],"contentPaths":["/coverLetter/companyName"],"reviewText":""},
                    {"claimId":"CLAIM-006","disposition":"SUPPORTED","evidenceIds":["REQUEST.GENERATION_INTENT","JOB.TITLE","JOB.COMPANY"],"contentPaths":["/coverLetter/openingParagraph"],"reviewText":""},
                    {"claimId":"CLAIM-007","disposition":"REWORDED","evidenceIds":["PROFILE.EMPLOYMENT.1.RESPONSIBILITIES","PROFILE.SKILL.1"],"contentPaths":["/coverLetter/bodyParagraphs/0"],"reviewText":""},
                    {"claimId":"CLAIM-008","disposition":"REWORDED","evidenceIds":["JOB.DESCRIPTION"],"contentPaths":["/coverLetter/bodyParagraphs/1","/coverLetter/bodyParagraphs/2"],"reviewText":""},
                    {"claimId":"CLAIM-009","disposition":"SUPPORTED","evidenceIds":["REQUEST.GENERATION_INTENT","JOB.TITLE","JOB.COMPANY"],"contentPaths":["/coverLetter/closingParagraph"],"reviewText":""},
                    {"claimId":"CLAIM-010","disposition":"REWORDED","evidenceIds":["PROFILE.TARGET_ROLE.1"],"contentPaths":["/cv/title"],"reviewText":""}
                  ]
                }
                """;
    }

    static String activeValidJson() {
        return """
                {
                  "cv": {
                    "title": "Tailored Developer CV",
                    "targetRole": "Java Developer",
                    "personalSummary": "A Java developer focused on useful services.",
                    "coreSkills": [],
                    "projects": [],
                    "qualifications": [],
                    "workHistory": []
                  },
                  "coverLetter": {
                    "title": "Developer Cover Letter",
                    "jobTitle": "Java Developer",
                    "companyName": "Example Ltd",
                    "greeting": "Dear Hiring Manager",
                    "openingParagraph": "Please consider my application for this role.",
                    "bodyParagraphs": ["My experience is a strong match.", "I build useful services.", "The role calls for useful services."],
                    "closingParagraph": "Thank you for considering my application.",
                    "signOff": "Yours faithfully"
                  },
                  "generationNotes": {"assumptionsMade":[],"missingInformation":[],"tailoringSummary":"Focused on Java."},
                  "claims": [
                    {"claimId":"CLAIM-001","disposition":"SUPPORTED","evidenceIds":["JOB.TITLE"],"contentPaths":["/cv/targetRole"],"reviewText":""},
                    {"claimId":"CLAIM-004","disposition":"SUPPORTED","evidenceIds":["JOB.TITLE"],"contentPaths":["/coverLetter/jobTitle"],"reviewText":""},
                    {"claimId":"CLAIM-005","disposition":"SUPPORTED","evidenceIds":["JOB.COMPANY"],"contentPaths":["/coverLetter/companyName"],"reviewText":""},
                    {"claimId":"CLAIM-007","disposition":"REWORDED","evidenceIds":["PROFILE.EMPLOYMENT.1.RESPONSIBILITIES","PROFILE.SKILL.1"],"contentPaths":["/coverLetter/bodyParagraphs/0"],"reviewText":""},
                    {"claimId":"CLAIM-008","disposition":"REWORDED","evidenceIds":["JOB.DESCRIPTION"],"contentPaths":["/coverLetter/bodyParagraphs/1","/coverLetter/bodyParagraphs/2"],"reviewText":""}
                  ],
                  "canonicalApplicationClaims": {
                    "opening": {
                      "claimId": "CLAIM-9001",
                      "disposition": "SUPPORTED",
                      "generationIntentEvidenceId": "REQUEST.GENERATION_INTENT",
                      "jobTitleEvidenceId": "JOB.TITLE",
                      "companyEvidenceId": "JOB.COMPANY",
                      "contentPath": "/coverLetter/openingParagraph",
                      "reviewText": ""
                    },
                    "closing": {
                      "claimId": "CLAIM-9002",
                      "disposition": "SUPPORTED",
                      "generationIntentEvidenceId": "REQUEST.GENERATION_INTENT",
                      "jobTitleEvidenceId": "JOB.TITLE",
                      "companyEvidenceId": "JOB.COMPANY",
                      "contentPath": "/coverLetter/closingParagraph",
                      "reviewText": ""
                    }
                  },
                  "personalSummaryClaim": {
                    "claimId": "CLAIM-9003",
                    "disposition": "REWORDED",
                    "evidenceIds": ["PROFILE.SKILL.1", "JOB.DESCRIPTION"],
                    "contentPath": "/cv/personalSummary",
                    "reviewText": ""
                  }
                }
                """;
    }

    private com.fasterxml.jackson.databind.JsonNode activeOutputSchema() throws Exception {
        try (java.io.InputStream input = getClass().getResourceAsStream(
                "/prompts/bundles/cv-cover-letter-1.5.9/output-schema.json")) {
            if (input == null) {
                throw new IllegalStateException("Active output schema fixture is missing.");
            }
            return new ObjectMapper().readTree(input);
        }
    }

    private JsonNode rollbackOutputSchema() throws Exception {
        try (java.io.InputStream input = getClass().getResourceAsStream(
                "/prompts/bundles/cv-cover-letter-1.5.3/output-schema.json")) {
            if (input == null) {
                throw new IllegalStateException("Rollback output schema fixture is missing.");
            }
            return new ObjectMapper().readTree(input);
        }
    }

    private JsonNode malformedActiveOutputSchema() throws Exception {
        JsonNode malformed = activeOutputSchema();
        ((ObjectNode) malformed.at(
                "/properties/claims/items/properties/claimId"))
                .put("pattern", "^CLAIM-[0-9]{3,4}$");
        return malformed;
    }
}
