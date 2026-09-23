package com.areatherm.api.dto;

import com.areatherm.optimizationrun.DesignCandidate;

import java.math.BigDecimal;

public record DesignCandidateResponse(
    Long id, String label, Long shelterDesignId, Long simulationId,
    BigDecimal comfortScore, BigDecimal retentionScore, BigDecimal solarScore,
    BigDecimal energyScore, BigDecimal costScore, BigDecimal weightedTotalScore,
    BigDecimal estimatedCostInr, boolean isRecommended
) {
    public static DesignCandidateResponse from(DesignCandidate c) {
        return new DesignCandidateResponse(
            c.getId(), c.getLabel(), c.getShelterDesign().getId(),
            c.getSimulation() != null ? c.getSimulation().getId() : null,
            c.getComfortScore(), c.getRetentionScore(), c.getSolarScore(),
            c.getEnergyScore(), c.getCostScore(), c.getWeightedTotalScore(),
            c.getEstimatedCostInr(), c.isRecommended()
        );
    }
}
