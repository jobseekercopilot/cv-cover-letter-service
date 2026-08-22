package com.jobseekercopilot.verification;

import com.jobseekercopilot.generated.cvcoverletterservice.api.CvCoverLetterControllerApi;
import com.jobseekercopilot.generated.cvcoverletterservice.client.ApiClient;
import com.jobseekercopilot.generated.cvcoverletterservice.model.DraftGenerationResponse;
import com.jobseekercopilot.generated.cvcoverletterservice.model.GenerateRequest;
import java.util.UUID;

final class ClientLinkage {

    private ClientLinkage() {
    }

    static CvCoverLetterControllerApi createClient() {
        ApiClient client = new ApiClient();
        client.setApiKey("compile-time-smoke-value");
        CvCoverLetterControllerApi api = new CvCoverLetterControllerApi(client);
        DraftCall authenticatedDraft = api::generateDraft;
        if (authenticatedDraft == null) {
            throw new IllegalStateException("Authenticated draft linkage is unavailable");
        }
        return api;
    }

    @FunctionalInterface
    private interface DraftCall {
        DraftGenerationResponse generate(
                String owner,
                UUID operationId,
                GenerateRequest request);
    }
}
