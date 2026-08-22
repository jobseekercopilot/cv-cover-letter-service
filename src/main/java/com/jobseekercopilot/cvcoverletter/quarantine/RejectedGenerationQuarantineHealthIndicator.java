package com.jobseekercopilot.cvcoverletter.quarantine;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class RejectedGenerationQuarantineHealthIndicator
        implements HealthIndicator {
    private final EncryptedRejectedGenerationRepository repository;

    public RejectedGenerationQuarantineHealthIndicator(
            EncryptedRejectedGenerationRepository repository) {
        this.repository = repository;
    }

    @Override
    public Health health() {
        EncryptedRejectedGenerationRepository.RepositoryHealth storage =
                repository.health();
        Health.Builder health = storage.ready() ? Health.up() : Health.down();
        return health
                .withDetail("enabled", storage.enabled())
                .withDetail("artifactCount", storage.artifactCount())
                .withDetail("usableBytes", storage.usableBytes())
                .build();
    }
}
