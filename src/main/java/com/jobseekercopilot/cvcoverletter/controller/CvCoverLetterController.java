package com.jobseekercopilot.cvcoverletter.controller;

import com.jobseekercopilot.cvcoverletter.dto.GenerateRequest;
import com.jobseekercopilot.cvcoverletter.dto.GenerateCvCoverLetterResponse;
import com.jobseekercopilot.cvcoverletter.dto.DraftGenerationEstimateResponse;
import com.jobseekercopilot.cvcoverletter.dto.DraftGenerationResponse;
import com.jobseekercopilot.cvcoverletter.dto.DraftOutputType;
import com.jobseekercopilot.cvcoverletter.dto.SelectedDraftGenerationRequest;
import com.jobseekercopilot.cvcoverletter.dto.SelectedDraftGenerationResponse;
import com.jobseekercopilot.cvcoverletter.exception.ApiError;
import com.jobseekercopilot.cvcoverletter.service.CvCoverLetterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import com.jobseekercopilot.cvcoverletter.security.CvCoverLetterIdentityFilter;
import com.jobseekercopilot.cvcoverletter.dto.ServiceIdentityError;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cv-cover-letter")
@RequiredArgsConstructor
public class CvCoverLetterController {

    private final CvCoverLetterService cvCoverLetterService;

    @PostMapping("/drafts/estimate")
    @Operation(
            summary = "Estimate the reservation required for a bounded draft",
            description = "Performs no provider, billing, document or application side effects.",
            parameters = @Parameter(
                    name = "X-Document-Owner",
                    in = ParameterIn.HEADER,
                    required = true,
                    description = "Owner context bound by the authenticated Gateway",
                    schema = @Schema(type = "string")))
    @SecurityRequirement(name = "serviceToken")
    public ResponseEntity<DraftGenerationEstimateResponse> estimateDraft(
            @RequestAttribute(CvCoverLetterIdentityFilter.OWNER_ATTRIBUTE)
            String documentOwner,
            @Valid @RequestBody GenerateRequest request) {
        return ResponseEntity.ok(
                cvCoverLetterService.estimateDraft(documentOwner, request));
    }

    @PostMapping("/drafts/{outputType}/estimate")
    @Operation(
            summary = "Estimate one explicitly selected document draft",
            description = "Prices only the selected CV or cover letter and performs no provider, billing, persistence or application side effects.",
            parameters = @Parameter(
                    name = "X-Document-Owner",
                    in = ParameterIn.HEADER,
                    required = true,
                    description = "Owner context bound by the authenticated Gateway",
                    schema = @Schema(type = "string")))
    @SecurityRequirement(name = "serviceToken")
    public ResponseEntity<DraftGenerationEstimateResponse> estimateSelectedDraft(
            @RequestAttribute(CvCoverLetterIdentityFilter.OWNER_ATTRIBUTE)
            String documentOwner,
            @PathVariable DraftOutputType outputType,
            @Valid @RequestBody SelectedDraftGenerationRequest request) {
        return ResponseEntity.ok(
                cvCoverLetterService.estimateSelectedDraft(
                        documentOwner, outputType, request));
    }

    @PostMapping("/drafts")
    @Operation(
            summary = "Generate bounded CV and cover-letter drafts",
            description = """
                    Calls the model and returns bounded draft content plus usage
                    evidence. Payment, persistence, approval, export and
                    application linkage remain the Gateway coordinator's
                    responsibility.
                    """,
            parameters = {
                    @Parameter(
                            name = "X-Document-Owner",
                            in = ParameterIn.HEADER,
                            required = true,
                            description = "Owner context bound by the authenticated Gateway",
                            schema = @Schema(type = "string")),
                    @Parameter(
                            name = "X-Generation-Operation-Id",
                            in = ParameterIn.HEADER,
                            required = true,
                            description = "Durable operation identity assigned by the Gateway",
                            schema = @Schema(type = "string", format = "uuid"))
            })
    @SecurityRequirement(name = "serviceToken")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Drafts generated"),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid bounded snapshots or operation identity",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(
                    responseCode = "401",
                    description = "Service authentication failed",
                    content = @Content(schema = @Schema(implementation = ServiceIdentityError.class))),
            @ApiResponse(
                    responseCode = "422",
                    description = "Model output could not be safely grounded in approved evidence",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(
                    responseCode = "502",
                    description = "Model provider call failed",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<DraftGenerationResponse> generateDraft(
            @RequestAttribute(CvCoverLetterIdentityFilter.OWNER_ATTRIBUTE)
            String documentOwner,
            @RequestHeader("X-Generation-Operation-Id") UUID operationId,
            @Valid @RequestBody GenerateRequest request) {
        return ResponseEntity.ok(
                cvCoverLetterService.generateDraft(
                        documentOwner, operationId, request));
    }

    @PostMapping("/drafts/{outputType}")
    @Operation(
            summary = "Generate one explicitly selected document draft",
            description = "Calls the model for only the selected CV or cover letter. The unselected document has no request, response, usage or billing evidence.",
            parameters = {
                    @Parameter(
                            name = "X-Document-Owner",
                            in = ParameterIn.HEADER,
                            required = true,
                            description = "Owner context bound by the authenticated Gateway",
                            schema = @Schema(type = "string")),
                    @Parameter(
                            name = "X-Generation-Operation-Id",
                            in = ParameterIn.HEADER,
                            required = true,
                            description = "Durable per-output operation identity assigned by the Gateway",
                            schema = @Schema(type = "string", format = "uuid"))
            })
    @SecurityRequirement(name = "serviceToken")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Selected draft generated"),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid selected output, bounded snapshot or operation identity",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(
                    responseCode = "401",
                    description = "Service authentication failed",
                    content = @Content(schema = @Schema(implementation = ServiceIdentityError.class))),
            @ApiResponse(
                    responseCode = "422",
                    description = "Selected model output could not be safely grounded",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(
                    responseCode = "502",
                    description = "Model provider call failed",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<SelectedDraftGenerationResponse> generateSelectedDraft(
            @RequestAttribute(CvCoverLetterIdentityFilter.OWNER_ATTRIBUTE)
            String documentOwner,
            @RequestHeader("X-Generation-Operation-Id") UUID operationId,
            @PathVariable DraftOutputType outputType,
            @Valid @RequestBody SelectedDraftGenerationRequest request) {
        return ResponseEntity.ok(
                cvCoverLetterService.generateSelectedDraft(
                        documentOwner,
                        operationId,
                        outputType,
                        request));
    }

    @PostMapping("/generate")
    @Operation(
            summary = "Generate from bounded canonical job and profile snapshots",
            parameters = @Parameter(
                    name = "X-Document-Owner",
                    in = ParameterIn.HEADER,
                    required = true,
                    description = "Owner context bound by the authenticated Gateway",
                    schema = @Schema(type = "string")))
    @SecurityRequirement(name = "serviceToken")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Documents generated and application recorded"),
            @ApiResponse(responseCode = "400", description = "Invalid request or owner context", content = @Content(schema = @Schema(oneOf = {ApiError.class, ServiceIdentityError.class}))),
            @ApiResponse(responseCode = "401", description = "Service authentication failed", content = @Content(schema = @Schema(implementation = ServiceIdentityError.class))),
            @ApiResponse(responseCode = "422", description = "Model output could not be safely grounded in approved evidence", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "502", description = "Generation or downstream service failure", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected internal error", content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<GenerateCvCoverLetterResponse> generate(
            @RequestAttribute(CvCoverLetterIdentityFilter.OWNER_ATTRIBUTE)
            String documentOwner,
            @Valid @RequestBody GenerateRequest request
    ) {
        return ResponseEntity.ok(cvCoverLetterService.generate(documentOwner, request));
    }
}
