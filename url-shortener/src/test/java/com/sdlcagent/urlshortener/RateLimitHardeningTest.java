package com.sdlcagent.urlshortener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sdlcagent.urlshortener.service.RateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(properties = {"ratelimit.max-requests=60", "ratelimit.window-seconds=60"})
@AutoConfigureMockMvc
class RateLimitHardeningTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    RateLimitService rateLimitService;

    @BeforeEach
    void resetLimiter() {
        rateLimitService.reset();
    }

    private static RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    @Test
    @DisplayName("AC-1 request 61 within the window gets 429 with Retry-After")
    void ac1_over60RequestsGets429WithRetryAfter() throws Exception {
        for (int i = 0; i < 60; i++) {
            mockMvc.perform(get("/nosuchcode").with(from("203.0.113.10")));
        }
        MvcResult result = mockMvc.perform(get("/nosuchcode").with(from("203.0.113.10")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andReturn();
        String retryAfter = result.getResponse().getHeader("Retry-After");
        long seconds = Long.parseLong(retryAfter.trim());
        org.junit.jupiter.api.Assertions.assertTrue(seconds >= 1 && seconds <= 60);
    }

    @Test
    @DisplayName("AC-1 the limit is tracked per client IP")
    void ac1_limitIsPerClientIp() throws Exception {
        for (int i = 0; i < 61; i++) {
            mockMvc.perform(get("/nosuchcode").with(from("203.0.113.11")));
        }
        mockMvc.perform(get("/nosuchcode").with(from("203.0.113.11")))
                .andExpect(status().isTooManyRequests());
        mockMvc.perform(get("/nosuchcode").with(from("203.0.113.12")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("AC-2 exactly 60 requests are processed normally")
    void ac2_requestsWithinLimitProcessedNormally() throws Exception {
        for (int i = 0; i < 60; i++) {
            MvcResult r = mockMvc.perform(get("/nosuchcode").with(from("203.0.113.20"))).andReturn();
            assertEquals(404, r.getResponse().getStatus());
        }
    }
}
