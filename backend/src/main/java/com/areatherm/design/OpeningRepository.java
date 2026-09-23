package com.areatherm.design;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OpeningRepository extends JpaRepository<Opening, Long> {

    List<Opening> findByShelterDesignId(Long shelterDesignId);
}
