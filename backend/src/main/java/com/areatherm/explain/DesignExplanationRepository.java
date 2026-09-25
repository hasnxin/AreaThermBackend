package com.areatherm.explain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DesignExplanationRepository extends JpaRepository<DesignExplanation, Long> {

    Optional<DesignExplanation> findBySimulationId(Long simulationId);
}
