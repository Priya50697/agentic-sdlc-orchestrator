package com.sdlcagent.urlshortener.dao;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UrlMappingRepository extends JpaRepository<UrlMapping, Long> {

    Optional<UrlMapping> findByCode(String code);

    boolean existsByCode(String code);
}
