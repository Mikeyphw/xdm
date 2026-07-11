# Avalonia browser integration and media probing

This phase adds a working browser-to-XDM capture boundary and the first media-specific services.

Implemented:

- authenticated loopback capture endpoint at `http://127.0.0.1:9614/capture`;
- persistent random per-user authentication token;
- 64 KiB request limit and strict HTTP/HTTPS URL validation;
- health endpoint at `http://127.0.0.1:9614/health`;
- support for filename, headers, cookies, referer, user agent, queue, category, and browser metadata;
- browser captures are submitted to the existing modern download manager;
- browser-health and last-capture information in the Avalonia UI;
- direct media, HLS, and DASH probing;
- HLS variant and DASH representation counts;
- secure DASH XML parsing with DTD processing disabled;
- protocol and media tests.

Example capture request:

```bash
curl \
  -H "X-XDM-Token: $XDM_TOKEN" \
  -H "Content-Type: application/json" \
  --data '{"url":"https://example.test/file.zip","browser":"Firefox"}' \
  http://127.0.0.1:9614/capture
```

Known transitional limits:

- this overlay exposes the capture protocol but does not yet ship revised Firefox/Chromium extension packages;
- HLS and DASH are detected and described, but segmented manifest downloading and stream merging remain for the extractor overlay;
- the capture port is fixed at 9614 for this phase;
- if `HttpListener` cannot bind, the application continues and displays the error on the Browser Integration page.
