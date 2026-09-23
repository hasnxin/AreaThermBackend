package com.areatherm.api;

import com.areatherm.api.dto.CreateShelterDesignRequest;
import com.areatherm.design.*;
import com.areatherm.material.Material;
import com.areatherm.material.MaterialRepository;
import com.areatherm.project.Project;
import com.areatherm.project.ProjectRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Implements API_SPEC.md's Shelter Design resource. This is the one
 * remaining piece of write-path plumbing SimulationController/
 * OptimizationRunController need a real shelterDesignId to exercise --
 * builds a ShelterDesign + Opening/ThermalMass graph directly from a
 * request (mirrors ShelterDesignService.persistFromDesign's shape, but
 * from raw client input rather than a physics-engine Design record).
 */
@RestController
@RequestMapping("/api/v1")
public class ShelterDesignController {

    private final ShelterDesignRepository shelterDesignRepository;
    private final OpeningRepository openingRepository;
    private final ThermalMassRepository thermalMassRepository;
    private final MaterialRepository materialRepository;
    private final ComfortProfileRepository comfortProfileRepository;
    private final ProjectRepository projectRepository;

    public ShelterDesignController(ShelterDesignRepository shelterDesignRepository, OpeningRepository openingRepository,
                                    ThermalMassRepository thermalMassRepository, MaterialRepository materialRepository,
                                    ComfortProfileRepository comfortProfileRepository, ProjectRepository projectRepository) {
        this.shelterDesignRepository = shelterDesignRepository;
        this.openingRepository = openingRepository;
        this.thermalMassRepository = thermalMassRepository;
        this.materialRepository = materialRepository;
        this.comfortProfileRepository = comfortProfileRepository;
        this.projectRepository = projectRepository;
    }

    @GetMapping("/projects/{projectId}/shelter-designs")
    public List<Map<String, Object>> listForProject(@PathVariable Long projectId) {
        return shelterDesignRepository.findByProjectId(projectId).stream()
            .map(d -> Map.<String, Object>of("id", d.getId(), "name", d.getName(), "shape", d.getShape().name()))
            .toList();
    }

    @PostMapping("/projects/{projectId}/shelter-designs")
    @Transactional
    public ResponseEntity<Map<String, Object>> create(@PathVariable Long projectId, @Valid @RequestBody CreateShelterDesignRequest req) {
        if (!projectId.equals(req.projectId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Path projectId does not match body projectId");
        }
        Project project = projectRepository.findById(projectId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No project with id " + projectId));
        ComfortProfile comfortProfile = comfortProfileRepository.findById(req.comfortProfileId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown comfort profile: " + req.comfortProfileId()));

        ShelterDesign d = new ShelterDesign();
        d.setProject(project);
        d.setName(req.name());
        d.setShape(parseEnum(ShelterDesign.Shape.class, req.shape(), "shape"));
        d.setLengthM(bd(req.length()));
        d.setWidthM(bd(req.width()));
        d.setHeightM(bd(req.height()));
        d.setDiameterM(bd(req.diameter()));
        d.setLengthAM(bd(req.lengthA()));
        d.setWidthAM(bd(req.widthA()));
        d.setLengthBM(bd(req.lengthB()));
        d.setWidthBM(bd(req.widthB()));
        d.setOrientation(parseEnum(ShelterDesign.Orientation.class, req.orientation(), "orientation"));
        d.setAzimuthDeg(bd(req.azimuthDeg()));
        d.setWallMaterial(requireMaterial(req.wallMaterialId()));
        d.setWallThicknessMm(bd(req.wallThicknessMm()));
        d.setRoofMaterial(requireMaterial(req.roofMaterialId()));
        d.setRoofThicknessMm(bd(req.roofThicknessMm()));
        d.setFloorMaterial(requireMaterial(req.floorMaterialId()));
        d.setFloorThicknessMm(bd(req.floorThicknessMm()));
        if (req.wallInsulationMaterialId() != null) {
            d.setWallInsulationMaterial(requireMaterial(req.wallInsulationMaterialId()));
            d.setWallInsulationThicknessMm(bd(req.wallInsulationThicknessMm()));
        }
        if (req.roofInsulationMaterialId() != null) {
            d.setRoofInsulationMaterial(requireMaterial(req.roofInsulationMaterialId()));
            d.setRoofInsulationThicknessMm(bd(req.roofInsulationThicknessMm()));
        }
        d.setAirLeakageAch(bd(req.airLeakageAch()));
        d.setComfortProfile(comfortProfile);
        d.setOccupancyCount(req.occupancyCount() != null ? req.occupancyCount() : 0);
        d.setOccupancyActivity(req.occupancyActivity() != null
            ? parseEnum(ShelterDesign.OccupancyActivity.class, req.occupancyActivity(), "occupancyActivity")
            : ShelterDesign.OccupancyActivity.SEATED);
        d.setInternalHeatGainW(bd(req.internalHeatGainW() != null ? req.internalHeatGainW() : 0));
        d.setGroundTempC(bd(req.groundTempC()));
        ShelterDesign saved = shelterDesignRepository.save(d);

        for (CreateShelterDesignRequest.OpeningRequest w : req.windows() != null ? req.windows() : List.<CreateShelterDesignRequest.OpeningRequest>of()) {
            openingRepository.save(toOpening(saved, Opening.OpeningType.WINDOW, w));
        }
        for (CreateShelterDesignRequest.OpeningRequest doorReq : req.doors() != null ? req.doors() : List.<CreateShelterDesignRequest.OpeningRequest>of()) {
            openingRepository.save(toOpening(saved, Opening.OpeningType.DOOR, doorReq));
        }
        if (req.thermalMass() != null) {
            ThermalMass tm = new ThermalMass();
            tm.setShelterDesign(saved);
            tm.setMaterial(requireMaterial(req.thermalMass().materialId()));
            tm.setMassKg(bd(req.thermalMass().massKg()));
            tm.setSurfaceAreaM2(bd(req.thermalMass().surfaceAreaM2()));
            tm.setExposure(req.thermalMass().exposure() != null
                ? parseEnum(ThermalMass.Exposure.class, req.thermalMass().exposure(), "thermalMass.exposure")
                : ThermalMass.Exposure.FLOOR);
            tm.setPcm(tm.getMaterial().getPcmMeltTempC() != null);
            thermalMassRepository.save(tm);
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", saved.getId(), "name", saved.getName()));
    }

    @GetMapping("/shelter-designs/{id}")
    public Map<String, Object> get(@PathVariable Long id) {
        ShelterDesign d = shelterDesignRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No shelter design with id " + id));
        return Map.of(
            "id", d.getId(), "name", d.getName(), "shape", d.getShape().name(),
            "orientation", d.getOrientation().name(), "wallMaterialId", d.getWallMaterial().getId(),
            "roofMaterialId", d.getRoofMaterial().getId(), "floorMaterialId", d.getFloorMaterial().getId(),
            "comfortProfileId", d.getComfortProfile().getId()
        );
    }

    private Material requireMaterial(Long id) {
        return materialRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown material id: " + id));
    }

    private Opening toOpening(ShelterDesign d, Opening.OpeningType type, CreateShelterDesignRequest.OpeningRequest req) {
        Opening o = new Opening();
        o.setShelterDesign(d);
        o.setOpeningType(type);
        o.setCount(req.count());
        o.setAreaEachM2(bd(req.areaEach()));
        o.setOrientation(parseEnum(Opening.OpeningOrientation.class, req.orientation(), "opening.orientation"));
        if (req.glazingMaterialId() != null) {
            o.setGlazingMaterial(requireMaterial(req.glazingMaterialId()));
        }
        return o;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String fieldName) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid " + fieldName + ": " + value);
        }
    }

    private static BigDecimal bd(Double v) {
        return v != null ? BigDecimal.valueOf(v) : null;
    }
}
