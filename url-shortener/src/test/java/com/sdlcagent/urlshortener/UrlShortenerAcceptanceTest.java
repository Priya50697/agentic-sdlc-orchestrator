package com.sdlcagent.urlshortener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdlcagent.urlshortener.dao.UrlMapping;
import com.sdlcagent.urlshortener.dao.UrlMappingRepository;
import com.sdlcagent.urlshortener.service.RateLimitService;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class UrlShortenerAcceptanceTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UrlMappingRepository repository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RateLimitService rateLimitService;

    @BeforeEach
    void clean() {
        repository.deleteAll();
        rateLimitService.reset();
    }

    private String json(String url) throws Exception {
        return objectMapper.writeValueAsString(Collections.singletonMap("url", url));
    }

    private MvcResult shorten(String url) throws Exception {
        return mockMvc.perform(post("/shorten").contentType(MediaType.APPLICATION_JSON).content(json(url)))
                .andReturn();
    }

    private String codeOf(MvcResult result) throws Exception {
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get("code").asText();
    }

    @Test
    @DisplayName("AC-1 POST /shorten with valid https URL returns 201 and non-empty code")
    void ac1_validHttpsUrlReturns201WithCode() throws Exception {
        mockMvc.perform(post("/shorten").contentType(MediaType.APPLICATION_JSON)
                        .content(json("https://example.com/some/long/path?x=1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").isString())
                .andExpect(jsonPath("$.code").isNotEmpty());
    }

    @Test
    @DisplayName("AC-1 POST /shorten with valid http URL returns 201 and non-empty code")
    void ac1_validHttpUrlReturns201WithCode() throws Exception {
        MvcResult result = shorten("http://example.org/page");
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(codeOf(result)).isNotBlank();
    }

    @Test
    @DisplayName("AC-2 code returned by POST /shorten is persisted with the original URL")
    void ac2_codeIsPersistedWithOriginalUrl() throws Exception {
        String url = "https://example.com/persisted?q=abc";
        String code = codeOf(shorten(url));

        assertThat(repository.findByCode(code)).isPresent();
        assertThat(repository.findByCode(code).get().getOriginalUrl()).isEqualTo(url);
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-2 a later GET /{code} resolves the code created by POST /shorten")
    void ac2_laterGetResolvesCreatedCode() throws Exception {
        String url = "https://example.com/resolve-me";
        String code = codeOf(shorten(url));

        mockMvc.perform(get("/" + code))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", url));
    }

    @Test
    @DisplayName("AC-2 submitting the same URL twice yields two distinct persisted codes")
    void ac2_sameUrlTwiceYieldsDistinctCodes() throws Exception {
        String url = "https://example.com/same";
        String first = codeOf(shorten(url));
        String second = codeOf(shorten(url));

        assertThat(first).isNotEqualTo(second);
        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("AC-3 GET /{code} for known code returns 302 with Location equal to original URL")
    void ac3_knownCodeRedirects() throws Exception {
        String url = "https://example.com/target?a=1&b=2";
        repository.save(new UrlMapping("abc1234", url));

        mockMvc.perform(get("/abc1234"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", url));
    }

    @Test
    @DisplayName("AC-4 GET /{code} for unknown code returns 404 with JSON error body")
    void ac4_unknownCodeReturns404() throws Exception {
        mockMvc.perform(get("/doesNotExist"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("AC-4 unknown code error response does not expose stack traces")
    void ac4_unknownCodeDoesNotLeakStackTrace() throws Exception {
        String body = mockMvc.perform(get("/zzzzzzz"))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("Exception").doesNotContain("at com.sdlcagent");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "not a url", "example.com", "ftp://example.com/file", "javascript:alert(1)",
            "file:///etc/passwd", "http://", "https:///path-only", "mailto:someone@example.com"})
    @DisplayName("AC-5 missing, blank, malformed or non-http(s) URL returns 400 and persists nothing")
    void ac5_invalidUrlReturns400AndPersistsNothing(String url) throws Exception {
        mockMvc.perform(post("/shorten").contentType(MediaType.APPLICATION_JSON).content(json(url)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400));

        assertThat(repository.count()).isZero();
    }

    @Test
    @DisplayName("AC-5 empty JSON object (url field missing) returns 400 and persists nothing")
    void ac5_missingFieldReturns400() throws Exception {
        mockMvc.perform(post("/shorten").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        assertThat(repository.count()).isZero();
    }

    @Test
    @DisplayName("AC-5 unparseable JSON body returns 400 and persists nothing")
    void ac5_malformedJsonBodyReturns400() throws Exception {
        mockMvc.perform(post("/shorten").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));

        assertThat(repository.count()).isZero();
    }

    @Test
    @DisplayName("AC-5 validation error response does not expose stack traces")
    void ac5_errorBodyHasNoStackTrace() throws Exception {
        String body = mockMvc.perform(post("/shorten").contentType(MediaType.APPLICATION_JSON)
                        .content(json("ftp://example.com")))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("at com.sdlcagent").doesNotContain("stackTrace");
    }

    @Test
    @DisplayName("AC-6 POST /shorten and GET /{code} work without any credentials")
    void ac6_noCredentialsRequired() throws Exception {
        MvcResult created = mockMvc.perform(post("/shorten").contentType(MediaType.APPLICATION_JSON)
                        .content(json("https://example.com/open")))
                .andExpect(status().isCreated())
                .andReturn();
        assertThat(created.getResponse().getHeader("WWW-Authenticate")).isNull();

        mockMvc.perform(get("/" + codeOf(created)))
                .andExpect(status().isFound());
    }

    @Test
    @DisplayName("AC-6 POST response exposes only the code field and no analytics data")
    void ac6_responseExposesNoAnalytics() throws Exception {
        MvcResult result = shorten("https://example.com/analytics");
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());

        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactly("code");
    }

    @Test
    @DisplayName("AC-6 stored mapping holds no visit count or analytics fields and redirects do not alter it")
    void ac6_noAnalyticsStored() throws Exception {
        List<String> fieldNames = new ArrayList<>();
        for (Field field : UrlMapping.class.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                fieldNames.add(field.getName().toLowerCase());
            }
        }
        assertThat(fieldNames).containsExactlyInAnyOrder("id", "code", "originalurl", "expiresat");

        String code = codeOf(shorten("https://example.com/visits"));
        mockMvc.perform(get("/" + code)).andExpect(status().isFound());
        mockMvc.perform(get("/" + code)).andExpect(status().isFound());

        assertThat(repository.count()).isEqualTo(1);
        assertThat(repository.findByCode(code).get().getOriginalUrl()).isEqualTo("https://example.com/visits");
    }
}
