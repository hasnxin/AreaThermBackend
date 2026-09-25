package com.areatherm.api.dto;

import com.areatherm.explain.DesignExplanation;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

public record DesignExplanationResponse(
    Long id, String status, String explanationText, List<String> citationsUsed,
    boolean citationHygieneOk, String modelName, String errorMessage
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static DesignExplanationResponse from(DesignExplanation e) {
        List<String> citations = List.of();
        if (e.getCitationsUsedJson() != null) {
            try {
                citations = MAPPER.readValue(e.getCitationsUsedJson(),
                    MAPPER.getTypeFactory().constructCollectionType(List.class, String.class));
            } catch (Exception ex) {
                // Non-critical metadata -- leave empty rather than fail the whole response over it.
            }
        }
        return new DesignExplanationResponse(e.getId(), e.getStatus().name(), e.getExplanationText(),
            citations, e.isCitationHygieneOk(), e.getModelName(), e.getErrorMessage());
    }
}
