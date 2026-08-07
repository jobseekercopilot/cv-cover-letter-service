package com.jobseekercopilot.cvcoverletter;

import com.jobseekercopilot.cvcoverletter.config.LlmProperties;
import com.jobseekercopilot.cvcoverletter.config.PromptBundleProperties;
import com.jobseekercopilot.cvcoverletter.config.RejectedGenerationQuarantineProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({
        LlmProperties.class,
        PromptBundleProperties.class,
        RejectedGenerationQuarantineProperties.class
})
public class CvCoverLetterServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CvCoverLetterServiceApplication.class, args);
    }
}
