package com.jobseekercopilot.cvcoverletter.controller;

import com.jobseekercopilot.cvcoverletter.dto.GenerateRequest;
import com.jobseekercopilot.cvcoverletter.dto.GenerateCvCoverLetterResponse;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/cv-cover-letter")
@RequiredArgsConstructor
public class CvCoverLetterController {

    private final CvCoverLetterService cvCoverLetterService;

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
