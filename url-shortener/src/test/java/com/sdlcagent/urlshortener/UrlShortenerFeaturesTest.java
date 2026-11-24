package com.sdlcagent.urlshortener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdlcagent.urlshortener.dao.UrlMapping;
import com.sdlcagent.urlshortener.dao.UrlMappingRepository;
import com.sdlcagent.urlshortener.service.RateLimitService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(properties = {"ratelimit.max-requests=5", "ratelimit.window-seconds=60"})
@AutoConfigureMockMvc
class UrlShortenerFeaturesTest {

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

    private ResultActions postRaw(String body) throws Exception {
        return mockMvc.perform(post("/shorten").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions postAlias(String url, String alias) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("url", url);
        m.put("customAlias", alias);
        return postRaw(objectMapper.writeValueAsString(m));
    }

    private RequestPostProcessor ip(String addr) {
        return request -> {
            request.setRemoteAddr(addr);
            return request;
        };
    }

    @Test
    @DisplayName("AC-1 custom alias becomes the short code and redirects to the target")
    void ac1_customAliasUsedAndRedirects() throws Exception {
        postAlias("https://example.com/alias-target", "my-alias_1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("my-alias_1"));

        mockMvc.perform(get("/my-alias_1"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/alias-target"));
    }

    @Test
    @DisplayName("AC-2 duplicate alias returns 409 and leaves the existing link unchanged")
    void ac2_duplicateAliasReturns409() throws Exception {
        postAlias("https://example.com/first", "taken1").andExpect(status().isCreated());

        postAlias("https://example.com/second", "taken1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        assertThat(repository.count()).isEqualTo(1);
        assertThat(repository.findByCode("taken1").get().getOriginalUrl()).isEqualTo("https://example.com/first");
    }

    @ParameterizedTest
    @ValueSource(strings = {"bad alias!", "a/b/c", "ab", "with.dot", "caf\u00e9123"})
    @DisplayName("AC-3 alias that is not URL-safe returns 400 and creates no link")
    void ac3_unsafeAliasReturns400(String alias) throws Exception {
        postAlias("https://example.com/x", alias)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        assertThat(repository.count()).isZero();
    }

    @Test
    @DisplayName("AC-4 positive ttlSeconds sets expiry at creation plus ttl; omitted ttl never expires")
    void ac4_ttlSetsExpiryAndOmittedMeansNone() throws Exception {
        Instant before = Instant.now();
        postRaw("{\"url\":\"https://example.com/ttl\",\"customAlias\":\"ttl-link\",\"ttlSeconds\":3600}")
                .andExpect(status().isCreated());
        Instant after = Instant.now();

        Instant expiresAt = repository.findByCode("ttl-link").get().getExpiresAt();
        assertThat(expiresAt).isNotNull();
        assertThat(expiresAt).isBetween(before.plusSeconds(3600).minusSeconds(2), after.plusSeconds(3600).plusSeconds(2));

        postRaw("{\"url\":\"https://example.com/nottl\",\"customAlias\":\"no-ttl\"}")
                .andExpect(status().isCreated());
        assertThat(repository.findByCode("no-ttl").get().getExpiresAt()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-5", "1.5", "\"abc\""})
    @DisplayName("AC-5 zero, negative, fractional or non-numeric ttlSeconds returns 400 and creates no link")
    void ac5_invalidTtlReturns400(String ttl) throws Exception {
        postRaw("{\"url\":\"https://example.com/bad-ttl\",\"ttlSeconds\":" + ttl + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        assertThat(repository.count()).isZero();
    }

    @Test
    @DisplayName("AC-6 expired link returns 410 while an unknown code still returns 404")
    void ac6_expiredReturns410UnknownReturns404() throws Exception {
        repository.save(new UrlMapping("expired1", "https://example.com/old", Instant.now().minusSeconds(60)));

        mockMvc.perform(get("/expired1"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.status").value(410));

        mockMvc.perform(get("/neverExisted"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("AC-7 exceeding the per-IP creation limit returns 429 while other IPs are unaffected")
    void ac7_rateLimitPerIp() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/shorten").with(ip("10.1.1.1")).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"url\":\"https://example.com/r" + i + "\"}"))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(post("/shorten").with(ip("10.1.1.1")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/over\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429));

        mockMvc.perform(post("/shorten").with(ip("10.2.2.2")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/other\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("AC-8 create without alias or ttl still generates a code and redirect works")
    void ac8_plainCreateAndRedirectUnchanged() throws Exception {
        String body = postRaw("{\"url\":\"https://example.com/plain\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String code = objectMapper.readTree(body).get("code").asText();

        assertThat(repository.findByCode(code).get().getExpiresAt()).isNull();

        mockMvc.perform(get("/" + code))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/plain"));
    }
}
