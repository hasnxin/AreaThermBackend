package com.areatherm.explain;

import com.areatherm.simulation.Simulation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

/**
 * One AI-generated natural-language explanation of a COMPLETE Simulation's
 * result -- same QUEUED -> RUNNING -> COMPLETE/FAILED async-job shape as
 * Era5Fetch/Simulation (see SimulationService.runAsync's javadoc for why
 * {@code @Transactional} covers the whole async method). Scoped 1:1 to a
 * simulation (unique constraint on simulation_id) -- unlike Era5Fetch's
 * shared-across-users cache, an explanation is specific to one user's
 * specific run, so there's no cross-user sharing to design for.
 *
 * Purely additive/narrative: nothing here ever feeds back into
 * ThermalEngine or any other calculation -- see CitationRegistry's javadoc
 * for the anti-hallucination approach that keeps this honest.
 */
@Entity
@Table(name = "design_explanation")
@Getter
@Setter
@NoArgsConstructor
public class DesignExplanation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "simulation_id", nullable = false, unique = true)
    private Simulation simulation;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private Status status = Status.QUEUED;

    @Column(name = "explanation_text", columnDefinition = "TEXT")
    private String explanationText;

    // JSON array of CitationRegistry short codes the model's output actually
    // mentioned (e.g. ["ASHRAE_55","ISO_8996"]) -- detected by scanning
    // against the registry, not asserted by the model itself.
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "citations_used", columnDefinition = "json")
    private String citationsUsedJson;

    // false if the output contained a citation-shaped token (e.g. "ISO 1234")
    // that doesn't match any CitationRegistry entry -- surfaced as an extra
    // UI warning rather than silently trusted. See CitationRegistry.scan().
    @Column(name = "citation_hygiene_ok", nullable = false)
    private boolean citationHygieneOk = true;

    @Column(name = "model_name", length = 100)
    private String modelName;

    @Column(name = "error_message", length = 4000)
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    public enum Status {
        QUEUED, RUNNING, COMPLETE, FAILED
    }
}
