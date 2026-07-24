package com.jobseekercopilot.cvcoverletter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "cv-cover-letter.security.gateway-token="
        + "test-only-cv-gateway-service-token-32-bytes")
@AutoConfigureMockMvc
class OpenApiExportTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void exportOpenApi() throws Exception {
        String spec = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode contract = objectMapper.readTree(spec);
        JsonNode generation =
                contract.path("paths").path("/api/v1/cv-cover-letter/generate").path("post");
        assertEquals("2.0.0", contract.path("info").path("version").asText());
        assertEquals(
                "X-Service-Token",
                contract.path("components")
                        .path("securitySchemes")
                        .path("serviceToken")
                        .path("name")
                        .asText());
        assertTrue(generation.path("security").toString().contains("serviceToken"));
        assertTrue(generation.path("parameters").toString().contains("X-Document-Owner"));
        assertFalse(spec.contains("X-User-Id"));
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/openapi.json"), spec);
    }
}
