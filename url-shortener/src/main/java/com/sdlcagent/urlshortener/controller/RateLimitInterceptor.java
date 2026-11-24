package com.sdlcagent.urlshortener.controller;

import com.sdlcagent.urlshortener.service.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Applies the per-IP rate limit using the socket remote address (X-Forwarded-For is ignored).
 * Throws RateLimitExceededException only when the limit is exceeded.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimitService rateLimitService;

    public RateLimitInterceptor(RateLimitService rateLimitService) {
        this.rateLimitService = rateLimitService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        rateLimitService.check(request.getRemoteAddr());
        return true;
    }
}
