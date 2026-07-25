package com.jobseekercopilot.cvcoverletter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
        assertEquals("3.0.0", contract.path("info").path("version").asText());
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
        JsonNode schemas = contract.path("components").path("schemas");
        for (String name : List.of(
                "ContactInputSnapshot",
                "EmploymentInput",
                "GenerateRequest",
                "JobInputSnapshot",
                "ProfileInputSnapshot",
                "QualificationInput",
                "SnapshotProvenance")) {
            assertFalse(schemas.path(name).path("additionalProperties").asBoolean(true));
        }
        assertTrue(schemas.path("GenerateRequest").path("required").toString()
                .contains("inputSchemaVersion"));
        assertTrue(schemas.path("GenerateRequest").path("required").toString()
                .contains("profile"));
        assertTrue(schemas.path("GenerateRequest").path("required").toString()
                .contains("job"));
        assertEquals(
                12000,
                schemas.path("JobInputSnapshot")
                        .path("properties")
                        .path("description")
                        .path("maxLength")
                        .asInt());
        assertEquals(
                40,
                schemas.path("ProfileInputSnapshot")
                        .path("properties")
                        .path("skills")
                        .path("maxItems")
                        .asInt());
        assertTrue(schemas.path("SnapshotProvenance")
                .path("properties")
                .path("owner")
                .path("enum")
                .toString()
                .contains("JOB_SERVICE"));
        assertFalse(schemas.has("UserProfile"));
        assertFalse(schemas.has("Job"));
        Files.createDirectories(Path.of("target"));
        Files.writeString(
                Path.of("target/openapi.json"),
                objectMapper.writeValueAsString(canonicalize(contract)));
    }

    private JsonNode canonicalize(JsonNode source) {
        if (source.isObject()) {
            ObjectNode canonical = objectMapper.createObjectNode();
            List<String> names = new ArrayList<>();
            source.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            names.forEach(name -> canonical.set(name, canonicalize(source.get(name))));
            return canonical;
        }
        if (source.isArray()) {
            ArrayNode canonical = objectMapper.createArrayNode();
            source.forEach(value -> canonical.add(canonicalize(value)));
            return canonical;
        }
        return source;
    }
}
