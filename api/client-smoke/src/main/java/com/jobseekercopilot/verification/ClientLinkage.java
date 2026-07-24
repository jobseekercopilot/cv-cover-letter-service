package com.jobseekercopilot.verification;

import com.jobseekercopilot.generated.cvcoverletterservice.api.CvCoverLetterControllerApi;
import com.jobseekercopilot.generated.cvcoverletterservice.client.ApiClient;
import com.jobseekercopilot.generated.cvcoverletterservice.model.GenerateCvCoverLetterResponse;
import com.jobseekercopilot.generated.cvcoverletterservice.model.GenerateRequest;
import java.util.function.BiFunction;

final class ClientLinkage {

    private ClientLinkage() {
    }

    static CvCoverLetterControllerApi createClient() {
        ApiClient client = new ApiClient();
        client.setApiKey("compile-time-smoke-value");
        CvCoverLetterControllerApi api = new CvCoverLetterControllerApi(client);
        BiFunction<String, GenerateRequest, GenerateCvCoverLetterResponse> authenticatedGenerate =
                api::generate;
        if (authenticatedGenerate == null) {
            throw new IllegalStateException("Authenticated generation linkage is unavailable");
        }
        return api;
    }
}
