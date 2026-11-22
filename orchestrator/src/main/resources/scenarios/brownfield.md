Extend the existing URL shortener with: (1) custom aliases - the caller may request a specific short code, which must
be unique and URL-safe; (2) expiry - a link may carry a TTL and an expired link responds 410 Gone; (3) a per-IP rate
limit on link creation that responds 429 when exceeded. Existing behaviour and tests must keep working.
