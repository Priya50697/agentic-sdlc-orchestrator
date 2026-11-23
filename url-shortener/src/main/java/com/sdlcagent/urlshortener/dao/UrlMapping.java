package com.sdlcagent.urlshortener.dao;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "url_mapping", uniqueConstraints = {
        @UniqueConstraint(name = "uk_url_mapping_code", columnNames = "code")
})
public class UrlMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, unique = true, length = 16)
    private String code;

    @Column(name = "original_url", nullable = false, length = 2048)
    private String originalUrl;

    protected UrlMapping() {
        // required by JPA
    }

    public UrlMapping(String code, String originalUrl) {
        this.code = code;
        this.originalUrl = originalUrl;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getOriginalUrl() {
        return originalUrl;
    }

    public void setOriginalUrl(String originalUrl) {
        this.originalUrl = originalUrl;
    }
}
