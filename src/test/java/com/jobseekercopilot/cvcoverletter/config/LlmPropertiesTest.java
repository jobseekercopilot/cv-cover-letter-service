package com.jobseekercopilot.cvcoverletter.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LlmPropertiesTest {

    @Test
    void defaultsDocumentGenerationToTheGatewayOutputCeiling() {
        assertEquals(4096, new LlmProperties().getMaxTokens());
        assertEquals(0.0, new LlmProperties().getTemperature());
    }
}
