package com.sdlcagent.urlshortener.service;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;
import com.sdlcagent.urlshortener.dao.UrlMapping;
import com.sdlcagent.urlshortener.dao.UrlMappingRepository;
import com.sdlcagent.urlshortener.dto.ShortenRequest;
import com.sdlcagent.urlshortener.util.CodeGenerator;
import com.sdlcagent.urlshortener.validator.AliasValidator;
import com.sdlcagent.urlshortener.validator.TtlValidator;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class UrlShortenerService {

    static final int MAX_ATTEMPTS = 10;

    private final UrlMappingRepository repository;
    private final CodeGenerator codeGenerator;
    private final Clock clock;
    private final AliasValidator aliasValidator;
    private final TtlValidator ttlValidator;

    public UrlShortenerService(UrlMappingRepository repository,
                               CodeGenerator codeGenerator,
                               Clock clock,
                               AliasValidator aliasValidator,
                               TtlValidator ttlValidator) {
        this.repository = repository;
        this.codeGenerator = codeGenerator;
        this.clock = clock;
        this.aliasValidator = aliasValidator;
        this.ttlValidator = ttlValidator;
    }

    public String shorten(String url) {
        return shorten(url, null, null);
    }

    public String shorten(ShortenRequest request) {
        return shorten(request.url(), request.customAlias(), request.ttlSeconds());
    }

    public String shorten(String url, String customAlias, JsonNode ttlSeconds) {
        if (customAlias != null) {
            aliasValidator.validate(customAlias);
        }
        Long ttl = ttlValidator.parse(ttlSeconds);
        Instant expiresAt = null;
        if (ttl != null) {
            try {
                expiresAt = clock.instant().plusSeconds(ttl);
            } catch (DateTimeException | ArithmeticException e) {
                throw new InvalidTtlException("ttlSeconds is out of range");
            }
        }

        if (customAlias != null) {
            return createWithAlias(url, customAlias, expiresAt);
        }
        return createWithGeneratedCode(url, expiresAt);
    }

    private String createWithAlias(String url, String alias, Instant expiresAt) {
        if (repository.existsByCode(alias)) {
            throw new AliasAlreadyExistsException(alias);
        }
        try {
            repository.saveAndFlush(new UrlMapping(alias, url, expiresAt));
            return alias;
        } catch (DataIntegrityViolationException e) {
            throw new AliasAlreadyExistsException(alias);
        }
    }

    private String createWithGeneratedCode(String url, Instant expiresAt) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String code = codeGenerator.generate();
            if (repository.existsByCode(code)) {
                continue;
            }
            try {
                repository.saveAndFlush(new UrlMapping(code, url, expiresAt));
                return code;
            } catch (DataIntegrityViolationException e) {
                // collision on unique constraint: regenerate and retry
            }
        }
        throw new IllegalStateException("Unable to generate a unique code");
    }

    public String resolve(String code) {
        UrlMapping mapping = repository.findByCode(code)
                .orElseThrow(() -> new CodeNotFoundException(code));
        Instant expiresAt = mapping.getExpiresAt();
        if (expiresAt != null && !expiresAt.isAfter(clock.instant())) {
            throw new LinkExpiredException(code);
        }
        return mapping.getOriginalUrl();
    }
}
