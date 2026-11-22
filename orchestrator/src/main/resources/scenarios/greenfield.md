Build a URL shortener service. Users POST a long URL and receive a short code; GET /{code} responds with a 302
redirect to the original URL. Every redirect is counted as a click and per-code click analytics are available over
the API. Before a URL is accepted it is checked by an outbound URL-safety provider (behind an interface, with a
local stub implementation); unsafe URLs are rejected. Data is stored in H2.
