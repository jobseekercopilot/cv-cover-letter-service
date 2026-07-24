package com.jobseekercopilot.verification;

import com.jobseekercopilot.generated.cvcoverletterservice.api.CvCoverLetterControllerApi;
import com.jobseekercopilot.generated.cvcoverletterservice.client.ApiClient;

final class ClientLinkage {

    private ClientLinkage() {
    }

    static CvCoverLetterControllerApi createClient() {
        return new CvCoverLetterControllerApi(new ApiClient());
    }
}
