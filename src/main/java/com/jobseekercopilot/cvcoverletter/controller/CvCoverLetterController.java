package com.jobseekercopilot.cvcoverletter.controller;

import com.jobseekercopilot.cvcoverletter.dto.GenerateRequest;
import com.jobseekercopilot.cvcoverletter.dto.GenerateCvCoverLetterResponse;
import com.jobseekercopilot.cvcoverletter.exception.ApiError;
import com.jobseekercopilot.cvcoverletter.service.CvCoverLetterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/cv-cover-letter")
@RequiredArgsConstructor
public class CvCoverLetterController {

    private final CvCoverLetterService cvCoverLetterService;

    @PostMapping("/generate")
    @Operation(summary = "Generate and store a tailored CV and cover letter")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Documents generated and application recorded"),
            @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "502", description = "Generation or downstream service failure", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected internal error", content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<GenerateCvCoverLetterResponse> generate(
            @RequestHeader(name = "X-User-Id", required = false) String userId,
            @Valid @RequestBody GenerateRequest request
    ) {
        if (userId == null || userId.isBlank()) {
            userId = request.getUserProfile().getUserId();
        }
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        request.getUserProfile().setUserId(userId);
        return ResponseEntity.ok(cvCoverLetterService.generate(request));
    }
}
