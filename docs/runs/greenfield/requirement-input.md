Build a minimal URL shortener service. POST /shorten accepts a JSON body with a long URL and returns a short
code; GET /{code} responds with a 302 redirect to the original URL, or 404 if the code is unknown. Invalid URLs are
rejected with 400. Store data in H2. No analytics, no authentication, and no external providers.
