package com.sdlcagent.urlshortener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sdlcagent.urlshortener.dao.UrlMappingRepository;
import com.sdlcagent.urlshortener.provider.ExternalCheckProvider;
import com.sdlcagent.urlshortener.service.ExternalCheckUnavailableException;
import com.sdlcagent.urlshortener.service.RateLimitService;
import com.sdlcagent.urlshortener.service.UnsafeUrlException;
import com.sdlcagent.urlshortener.validator.SsrfUrlValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {"ratelimit.max-requests=100000"})
@AutoConfigureMockMvc
class SsrfAndFailureHandlingTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    RateLimitService rateLimitService;

    @Autowired
    UrlMappingRepository repository;

    @MockitoBean
    ExternalCheckProvider externalCheckProvider;

    @BeforeEach
    void setUp() {
        rateLimitService.reset();
        repository.deleteAll();
    }

    private MvcResult shorten(String url) throws Exception {
        return mockMvc.perform(post("/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"" + url + "\"}"))
                .andReturn();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1/",
            "http://localhost/",
            "http://10.0.0.5/",
            "http://172.16.0.1/",
            "http://192.168.1.1/",
            "http://169.254.169.254/latest/meta-data",
            "http://[::1]/",
            "http://[fc00::1]/",
            "http://[fd12:3456::1]/",
            "http://0.0.0.0/"
    })
    @DisplayName("AC-3 private, loopback, link-local and unspecified hosts are rejected with 400 and no outbound call")
    void ac3_internalHostsRejectedWith400(String url) throws Exception {
        mockMvc.perform(post("/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"" + url + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").isNotEmpty());
        verifyNoInteractions(externalCheckProvider);
        assertThat(repository.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1/",
            "http://localhost/",
            "http://10.1.2.3/",
            "http://192.168.0.10/",
            "http://169.254.169.254/",
            "http://[::1]/",
            "http://[fc00::1]/",
            "http://0.0.0.0/"
    })
    @DisplayName("AC-3 validator throws UnsafeUrlException with a reason for internal addresses")
    void ac3_validatorRejectsInternalAddressesWithReason(String url) {
        SsrfUrlValidator validator = new SsrfUrlValidator();
        assertThatThrownBy(() -> validator.validate(url))
                .isInstanceOf(UnsafeUrlException.class)
                .satisfies(e -> assertThat(((UnsafeUrlException) e).getReason()).isNotBlank());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ftp://example.com/file",
            "file:///etc/passwd",
            "gopher://example.com/",
            "javascript:alert(1)"
    })
    @DisplayName("AC-4 non-http(s) schemes are rejected with 400")
    void ac4_nonHttpSchemesRejectedWith400(String url) throws Exception {
        mockMvc.perform(post("/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"" + url + "\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(externalCheckProvider);
        assertThat(repository.count()).isZero();
    }

    @Test
    @DisplayName("AC-5 a public host passes validation and reaches the external check")
    void ac5_publicHostProceedsToExternalCheck() throws Exception {
        MvcResult result = shorten("http://8.8.8.8/path");
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        verify(externalCheckProvider, times(1)).check(any(), any());
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-5 validator accepts a public literal address and returns it")
    void ac5_validatorAcceptsPublicAddress() {
        SsrfUrlValidator validator = new SsrfUrlValidator();
        assertThat(validator.validate("https://8.8.8.8/")).isNotEmpty();
    }

    @Test
    @DisplayName("AC-7 persistent external check failure returns 503 JSON without stack trace")
    void ac7_externalCheckFailureReturns503() throws Exception {
        doThrow(new ExternalCheckUnavailableException("check failed"))
                .when(externalCheckProvider).check(any(), any());

        MvcResult result = mockMvc.perform(post("/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"http://8.8.8.8/\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getContentType()).contains("json");
        assertThat(body).doesNotContain("stackTrace").doesNotContain("at com.").doesNotContain("Exception");
        assertThat(repository.count()).isZero();
    }
}
