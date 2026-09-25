package com.areatherm.explain;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds the {@code areatherm.ollama.*} block in application.yml. Unlike
 * Era5Properties (a remote service gated on an API key), Ollama is a local,
 * optional runtime -- there's no key to be "configured" with. Real
 * availability is a live reachability + model-presence check instead, see
 * {@link DesignExplanationService#isAvailable()} / {@link OllamaClient#isModelAvailable()}.
 */
@Component
@ConfigurationProperties(prefix = "areatherm.ollama")
@Getter
@Setter
public class OllamaProperties {

    private String baseUrl = "http://localhost:11434";
    private String model = "qwen2.5:7b-instruct";
    // CPU-only inference for a few paragraphs on a 7B model can genuinely
    // take 1-3 minutes on a laptop with no dedicated GPU -- generous by
    // design, matching the same "real observed latency, not a guess"
    // reasoning behind era5's poll-timeout-ms.
    private long timeoutMs = 180000;
    private long availabilityCheckTimeoutMs = 3000;
}
