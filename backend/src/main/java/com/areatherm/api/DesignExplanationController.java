package com.areatherm.api;

import com.areatherm.api.dto.DesignExplanationResponse;
import com.areatherm.explain.DesignExplanation;
import com.areatherm.explain.DesignExplanationService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Optional, opt-in AI-generated natural-language explanation of a COMPLETE
 * simulation's result, via a local Ollama model (see OllamaProperties for
 * how it's configured, and DesignExplanationService for how the prompt is
 * grounded against real computed values only). Same 202-then-poll shape as
 * /simulations|/optimization-runs|/climate/era5-fetch.
 */
@RestController
@RequestMapping("/api/v1/simulations/{simulationId}/explain")
public class DesignExplanationController {

    private final DesignExplanationService service;

    public DesignExplanationController(DesignExplanationService service) {
        this.service = service;
    }

    @GetMapping("/availability")
    public Map<String, Boolean> availability() {
        return Map.of("available", service.isAvailable());
    }

    @PostMapping
    public ResponseEntity<DesignExplanationResponse> create(@PathVariable Long simulationId) {
        if (!service.isAvailable()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "The local design-explanation model is not available on this server (Ollama not running, or the model hasn't finished downloading yet).");
        }
        DesignExplanation exp = service.findOrCreateQueued(simulationId);
        // Only a freshly-created row needs kicking off -- an existing
        // RUNNING/COMPLETE row is already handled. Called from here, not
        // from inside findOrCreateQueued itself, so @Async actually applies
        // -- see DesignExplanationService.runAsync's javadoc.
        if (exp.getStatus() == DesignExplanation.Status.QUEUED) {
            service.runAsync(exp.getId());
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(DesignExplanationResponse.from(exp));
    }

    @GetMapping
    public DesignExplanationResponse get(@PathVariable Long simulationId) {
        DesignExplanation exp = service.findBySimulationId(simulationId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "No explanation has been requested yet for simulation " + simulationId));
        return DesignExplanationResponse.from(exp);
    }
}
