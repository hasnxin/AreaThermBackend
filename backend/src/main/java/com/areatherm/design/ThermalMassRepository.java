package com.areatherm.design;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ThermalMassRepository extends JpaRepository<ThermalMass, Long> {

    Optional<ThermalMass> findByShelterDesignId(Long shelterDesignId);
}
