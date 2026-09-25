package com.areatherm.explain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Raw HTTP client for a local Ollama server's REST API
 * (POST /api/generate, GET /api/tags -- see https://github.com/ollama/ollama/blob/main/docs/api.md).
 * No API key: a default local Ollama install has no auth. Non-streaming
 * generation ({@code stream: false}) is used deliberately -- the whole
 * response comes back as one JSON object once generation finishes, instead
 * of an NDJSON stream this backend would have to reassemble for no benefit
 * (the async QUEUED/RUNNING/COMPLETE job wrapper already covers the wait).
 */
@Component
public class OllamaClient {

    private final OllamaProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public OllamaClient(OllamaProperties props) {
        this.props = props;
    }

    /** True only if the server responds AND the configured model has actually finished pulling. */
    public boolean isModelAvailable() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(props.getBaseUrl() + "/api/tags"))
                .timeout(Duration.ofMillis(props.getAvailabilityCheckTimeoutMs()))
                .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return false;
            JsonNode models = mapper.readTree(resp.body()).path("models");
            for (JsonNode m : models) {
                String name = m.path("name").asText("");
                // Ollama's /api/tags always includes the tag (e.g.
                // "qwen2.5:7b-instruct") -- also accept a bare-name match in
                // case the configured model omits the implicit ":latest".
                if (name.equals(props.getModel()) || name.equals(props.getModel() + ":latest")) return true;
            }
            return false;
        } catch (Exception e) {
            return false; // server not running, DNS/connect failure, malformed response -- all just mean "not available"
        }
    }

    public String generate(String prompt) {
        try {
            var body = mapper.createObjectNode();
            body.put("model", props.getModel());
            body.put("prompt", prompt);
            body.put("stream", false);
            HttpRequest req = HttpRequest.newBuilder(URI.create(props.getBaseUrl() + "/api/generate"))
                .timeout(Duration.ofMillis(props.getTimeoutMs()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body)))
                .build();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) {
                throw new ExplanationException("Ollama returned HTTP " + resp.statusCode() + ": "
                    + new String(resp.body(), StandardCharsets.UTF_8));
            }
            JsonNode json = mapper.readTree(resp.body());
            String text = json.path("response").asText(null);
            if (text == null || text.isBlank()) {
                throw new ExplanationException("Ollama's response had no text: " + json);
            }
            return text;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ExplanationException("Could not reach the local Ollama server at " + props.getBaseUrl()
                + " -- is it running? (ollama serve)", e);
        }
    }
}
