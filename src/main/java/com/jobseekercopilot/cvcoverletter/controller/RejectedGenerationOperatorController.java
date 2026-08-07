package com.jobseekercopilot.cvcoverletter.controller;

import com.jobseekercopilot.cvcoverletter.dto.GenerateRequest;
import com.jobseekercopilot.cvcoverletter.dto.RejectedGenerationDeletionResponse;
import com.jobseekercopilot.cvcoverletter.dto.RejectedGenerationMetadataResponse;
import com.jobseekercopilot.cvcoverletter.dto.RejectedGenerationReplayResponse;
import com.jobseekercopilot.cvcoverletter.quarantine.RejectedGenerationQuarantineService;
import com.jobseekercopilot.cvcoverletter.security.RejectedGenerationOperatorFilter;
import com.jobseekercopilot.cvcoverletter.service.CvCoverLetterService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
@RequestMapping("/internal/v1/cv-cover-letter/rejected-generations")
@RequiredArgsConstructor
public class RejectedGenerationOperatorController {
    private final CvCoverLetterService cvCoverLetterService;
    private final RejectedGenerationQuarantineService quarantineService;

    @GetMapping("/{operationId}")
    public ResponseEntity<RejectedGenerationMetadataResponse> metadata(
            @RequestAttribute(RejectedGenerationOperatorFilter.OWNER_ATTRIBUTE)
            String documentOwner,
            @PathVariable UUID operationId) {
        return ResponseEntity.ok(
                quarantineService.metadata(documentOwner, operationId));
    }

    @PostMapping("/{operationId}/replay")
    public ResponseEntity<RejectedGenerationReplayResponse> replay(
            @RequestAttribute(RejectedGenerationOperatorFilter.OWNER_ATTRIBUTE)
            String documentOwner,
            @PathVariable UUID operationId,
            @Valid @RequestBody GenerateRequest request) {
        return ResponseEntity.ok(
                cvCoverLetterService.replayRejectedDraft(
                        documentOwner,
                        operationId,
                        request));
    }

    @DeleteMapping("/{operationId}")
    public ResponseEntity<RejectedGenerationDeletionResponse> delete(
            @RequestAttribute(RejectedGenerationOperatorFilter.OWNER_ATTRIBUTE)
            String documentOwner,
            @PathVariable UUID operationId) {
        return ResponseEntity.ok(
                quarantineService.delete(documentOwner, operationId));
    }
}
