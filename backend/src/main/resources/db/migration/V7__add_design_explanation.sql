-- AI-generated natural-language explanation of one COMPLETE simulation's
-- result, via a local Ollama model (optional, opt-in -- see
-- OllamaProperties). Scoped 1:1 to a simulation, unlike era5_fetch's
-- shared-across-users cache -- an explanation is specific to one user's
-- specific run, so there's no cross-user sharing to design for.
CREATE TABLE design_explanation (
  id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
  simulation_id       BIGINT NOT NULL,
  status              VARCHAR(10) NOT NULL DEFAULT 'QUEUED'
                        CHECK (status IN ('QUEUED','RUNNING','COMPLETE','FAILED')),
  explanation_text    TEXT,
  -- JSON array of CitationRegistry short codes actually detected in the
  -- model's output (e.g. ["ASHRAE_55","ISO_8996"]) -- see CitationRegistry.scan().
  citations_used      JSON,
  -- FALSE if the output contained a citation-shaped token that doesn't
  -- match any CitationRegistry entry -- surfaced as an extra UI warning
  -- rather than silently trusted.
  citation_hygiene_ok BOOLEAN NOT NULL DEFAULT TRUE,
  model_name          VARCHAR(100),
  error_message       VARCHAR(4000),
  created_at          TIMESTAMP NOT NULL,
  completed_at        TIMESTAMP,
  CONSTRAINT uq_design_explanation_simulation UNIQUE (simulation_id),
  CONSTRAINT fk_design_explanation_simulation FOREIGN KEY (simulation_id) REFERENCES simulation(id)
);
