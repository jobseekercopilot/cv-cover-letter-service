package com.jobseekercopilot.cvcoverletter.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "rejected-generation-quarantine")
public class RejectedGenerationQuarantineProperties {
    private boolean enabled;
    private String storageDirectory = "/var/lib/cv-cover-letter/rejected-generations";
    private String encryptionKeyBase64 = "";
    private String operatorToken = "";
    private Duration retention = Duration.ofHours(24);
    private Duration cleanupInterval = Duration.ofMinutes(15);
    private int maxArtifactBytes = 262_144;
    private int maxArtifacts = 1_000;
    private int maxReplayEvents = 100;
    private boolean allowOperationBoundContextDrift;
}
