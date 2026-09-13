package com.jobseekercopilot.cvcoverletter.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.jobseekercopilot.cvcoverletter.logging.CorrelationIdFilter;
import java.time.Duration;
import org.slf4j.MDC;
import org.openapitools.jackson.nullable.JsonNullableModule;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.DefaultUriBuilderFactory;

final class GeneratedApiClientRestTemplateFactory {
    // Conservative defaults for fast downstream calls. A downstream that hangs
    // must fail fast rather than tie up a worker thread indefinitely.
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(30);

    private GeneratedApiClientRestTemplateFactory() {
    }

    static RestTemplate create() {
        return create(DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT);
    }

    static RestTemplate create(Duration connectTimeout, Duration readTimeout) {
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()
                || readTimeout == null || readTimeout.isZero() || readTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "Downstream client timeouts must be positive.");
        }
        RestTemplate restTemplate = new RestTemplate();
        SimpleClientHttpRequestFactory timeoutFactory = new SimpleClientHttpRequestFactory();
        timeoutFactory.setConnectTimeout((int) connectTimeout.toMillis());
        timeoutFactory.setReadTimeout((int) readTimeout.toMillis());
        restTemplate.setRequestFactory(new BufferingClientHttpRequestFactory(timeoutFactory));
        restTemplate.getInterceptors().add((request, body, execution) -> {
            String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
            if (StringUtils.hasText(correlationId)) {
                request.getHeaders().set(CorrelationIdFilter.HEADER_NAME, correlationId);
            }
            return execution.execute(request, body);
        });

        DefaultUriBuilderFactory uriBuilderFactory = new DefaultUriBuilderFactory();
        uriBuilderFactory.setEncodingMode(DefaultUriBuilderFactory.EncodingMode.VALUES_ONLY);
        restTemplate.setUriTemplateHandler(uriBuilderFactory);

        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .registerModule(new JsonNullableModule())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        restTemplate.getMessageConverters().stream()
                .filter(MappingJackson2HttpMessageConverter.class::isInstance)
                .map(MappingJackson2HttpMessageConverter.class::cast)
                .forEach(converter -> converter.setObjectMapper(objectMapper));

        return restTemplate;
    }
}
