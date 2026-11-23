package com.sdlcagent.urlshortener.service;

import com.sdlcagent.urlshortener.dao.UrlMapping;
import com.sdlcagent.urlshortener.dao.UrlMappingRepository;
import com.sdlcagent.urlshortener.util.CodeGenerator;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class UrlShortenerService {

    static final int MAX_ATTEMPTS = 10;

    private final UrlMappingRepository repository;
    private final CodeGenerator codeGenerator;

    public UrlShortenerService(UrlMappingRepository repository, CodeGenerator codeGenerator) {
        this.repository = repository;
        this.codeGenerator = codeGenerator;
    }

    public String shorten(String url) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String code = codeGenerator.generate();
            if (repository.existsByCode(code)) {
                continue;
            }
            try {
                repository.saveAndFlush(new UrlMapping(code, url));
                return code;
            } catch (DataIntegrityViolationException e) {
                // collision on unique constraint: regenerate and retry
            }
        }
        throw new IllegalStateException("Unable to generate a unique code");
    }

    public String resolve(String code) {
        return repository.findByCode(code)
                .map(UrlMapping::getOriginalUrl)
                .orElseThrow(() -> new CodeNotFoundException(code));
    }
}
