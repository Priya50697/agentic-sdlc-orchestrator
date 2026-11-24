Extend the existing URL shortener with: (1) custom aliases - the caller may request a specific short code, which must
be unique and URL-safe; (2) expiry - a link may carry a TTL (ttlSeconds, a strict integer: zero, negative, fractional
such as 1.5, or non-numeric values return 400) and an expired link responds 410 Gone; (3) a per-IP rate limit on link
creation that responds 429 when exceeded. Existing behaviour and tests must keep working, with one allowed exception:
the existing test that asserts the exact field list of the stored entity (AC-6 "no analytics stored") may be updated to
include the new nullable expiry field, because the expiry feature requires that column.
