package com.areatherm.explain;

import com.areatherm.climate.ClimateProfile;
import com.areatherm.climate.Location;
import com.areatherm.design.ShelterDesign;
import com.areatherm.material.Material;
import com.areatherm.simulation.Simulation;
import com.areatherm.simulation.SimulationRepository;
import com.areatherm.simulation.SimulationSummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Same QUEUED -> RUNNING -> COMPLETE/FAILED async-job shape as
 * Era5Service/SimulationService (see SimulationService.runAsync's javadoc
 * for why {@code @Transactional} covers the whole async method here too).
 * Unlike those, the "work" is a local LLM call (OllamaClient), not a remote
 * fetch or a physics computation -- and unlike Era5Fetch's shared-across-
 * users cache, one DesignExplanation belongs to exactly one Simulation.
 *
 * Grounding / anti-hallucination design (see CitationRegistry for the
 * citation half of this): the prompt built in {@link #buildPrompt} is
 * assembled entirely from real, already-computed values read off the
 * Simulation's own persisted {@link SimulationSummary} plus its linked
 * ShelterDesign/ClimateProfile/Location -- the model is never asked to
 * compute or estimate anything, only to explain numbers it's handed. It is
 * explicitly given the exact fixed citation list and told not to cite
 * anything else; {@link CitationRegistry#scan} independently re-checks the
 * raw output afterward rather than trusting the model's own compliance
 * with that instruction.
 */
@Service
public class DesignExplanationService {

    private static final Logger log = LoggerFactory.getLogger(DesignExplanationService.class);

    private final DesignExplanationRepository repository;
    private final SimulationRepository simulationRepository;
    private final OllamaClient client;
    private final OllamaProperties props;
    private final ObjectMapper objectMapper;

    public DesignExplanationService(DesignExplanationRepository repository, SimulationRepository simulationRepository,
                                     OllamaClient client, OllamaProperties props, ObjectMapper objectMapper) {
        this.repository = repository;
        this.simulationRepository = simulationRepository;
        this.client = client;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    public boolean isAvailable() {
        return client.isModelAvailable();
    }

    @Transactional(readOnly = true)
    public Optional<DesignExplanation> findBySimulationId(Long simulationId) {
        return repository.findBySimulationId(simulationId);
    }

    /**
     * Returns an existing explanation for this simulation if one exists and
     * didn't fail, otherwise creates a fresh QUEUED row. Deliberately does
     * NOT call runAsync itself -- see Era5Service.findOrCreateQueued's
     * javadoc for why a same-class self-invocation would silently defeat
     * {@code @Async}; the caller (DesignExplanationController) calls
     * runAsync separately, from outside this class.
     */
    @Transactional
    public DesignExplanation findOrCreateQueued(Long simulationId) {
        Simulation sim = simulationRepository.findById(simulationId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown simulation: " + simulationId));
        if (sim.getStatus() != Simulation.Status.COMPLETE) {
            throw new IllegalStateException("Simulation " + simulationId + " is not COMPLETE yet -- nothing to explain.");
        }
        Optional<DesignExplanation> existing = repository.findBySimulationId(simulationId);
        if (existing.isPresent() && existing.get().getStatus() != DesignExplanation.Status.FAILED) {
            return existing.get();
        }
        DesignExplanation exp = existing.orElseGet(DesignExplanation::new);
        exp.setSimulation(sim);
        exp.setStatus(DesignExplanation.Status.QUEUED);
        exp.setErrorMessage(null);
        exp.setExplanationText(null);
        return repository.save(exp);
    }

    /**
     * Must be called from OUTSIDE this class (e.g. DesignExplanationController),
     * never as a same-class self-invocation from findOrCreateQueued above --
     * see its javadoc for why that silently defeats {@code @Async}.
     */
    @Async("engineTaskExecutor")
    @Transactional
    public void runAsync(Long explanationId) {
        DesignExplanation exp = repository.findById(explanationId).orElse(null);
        if (exp == null) {
            log.warn("runAsync: design_explanation {} no longer exists", explanationId);
            return;
        }
        exp.setStatus(DesignExplanation.Status.RUNNING);
        repository.save(exp);

        try {
            Simulation sim = exp.getSimulation();
            SimulationSummary summary = objectMapper.readValue(sim.getSummaryJson(), SimulationSummary.class);
            String prompt = buildPrompt(sim, summary);

            String raw = client.generate(prompt);
            CitationRegistry.ScanResult scan = CitationRegistry.scan(raw);

            exp.setExplanationText(raw.trim());
            exp.setCitationsUsedJson(objectMapper.writeValueAsString(scan.usedCodes()));
            exp.setCitationHygieneOk(scan.hygieneOk());
            exp.setModelName(props.getModel());
            exp.setStatus(DesignExplanation.Status.COMPLETE);
            exp.setCompletedAt(LocalDateTime.now());
            repository.save(exp);
        } catch (Exception e) {
            log.error("Design explanation {} failed", explanationId, e);
            exp.setStatus(DesignExplanation.Status.FAILED);
            exp.setErrorMessage(String.valueOf(e.getMessage()));
            repository.save(exp);
        }
    }

    // ---- Prompt construction -----------------------------------------------

    private String buildPrompt(Simulation sim, SimulationSummary summary) {
        ShelterDesign d = sim.getShelterDesign();
        ClimateProfile climate = sim.getClimateProfile();
        Location loc = climate.getLocation();

        StringBuilder sb = new StringBuilder();
        sb.append("You are explaining a real, physics-based thermal simulation result inside AreaTherm, ")
            .append("a passive-shelter thermal-design tool built for a DRDO (Indian defence research) shelter-design ")
            .append("program. The reader is a non-specialist evaluator. Accuracy and honesty matter more than eloquence.\n\n")
            .append("STRICT RULES:\n")
            .append("1. Use ONLY the numbers given below. Never invent, estimate, or round a figure that isn't given.\n")
            .append("2. You may cite ONLY from this fixed reference list, and only where genuinely relevant. ")
            .append("Never cite anything not on this list, including real standards you may know from elsewhere:\n")
            .append(CitationRegistry.allAsPromptText())
            .append("3. The 'Thermal Comfort Score' below is a custom, project-defined weighted index -- it is NOT ")
            .append("PMV/PPD or any other standard comfort index, even where ASHRAE 55 is cited for general context.\n")
            .append("4. If you are not sure something is true, say so rather than guessing.\n")
            .append("5. Write 3-5 short plain-language paragraphs: what the numbers mean, why the design performs this ")
            .append("way given the climate, and (only if the comfort score is below 80) which specific input from the ")
            .append("data below most likely limits it. No markdown headers, no bullet lists -- plain prose.\n\n")
            .append("=== SITE ===\n")
            .append(fmtLocation(loc)).append('\n')
            .append(fmtClimate(climate)).append("\n\n")
            .append("=== SHELTER DESIGN ===\n")
            .append(fmtDesign(d)).append("\n\n")
            .append("=== SIMULATION RESULT ===\n")
            .append(fmtResult(summary));

        return sb.toString();
    }

    private static String fmtLocation(Location loc) {
        if (loc == null) return "Location: not recorded.";
        String label = nonBlankJoin(loc.getVillage(), loc.getDistrict(), loc.getState(), loc.getCountry());
        return String.format(Locale.ROOT, "Location: %s (lat %.3f, lon %.3f, elevation %s m)",
            label.isBlank() ? "unnamed" : label, loc.getLatitude(), loc.getLongitude(),
            loc.getElevationM() != null ? loc.getElevationM().toString() : "unknown");
    }

    private static String fmtClimate(ClimateProfile c) {
        StringBuilder sb = new StringBuilder("Climate data source: ").append(c.getDataSource());
        if (c.getAmbientTempMinC() != null && c.getAmbientTempMaxC() != null) {
            sb.append(String.format(Locale.ROOT, "%nAmbient temperature range (loaded period): %.1f to %.1f C",
                c.getAmbientTempMinC(), c.getAmbientTempMaxC()));
        }
        if (c.getAvgTempCAnnual() != null) {
            sb.append(String.format(Locale.ROOT, "%nAnnual mean temperature: %.1f C", c.getAvgTempCAnnual()));
        }
        if (c.getSolarIrradianceKwhM2Yr() != null) {
            sb.append(String.format(Locale.ROOT, "%nAnnual solar irradiance: %.0f kWh/m2/yr", c.getSolarIrradianceKwhM2Yr()));
        }
        if (c.getAvgWindSpeedMs() != null) {
            sb.append(String.format(Locale.ROOT, "%nAverage wind speed: %.1f m/s", c.getAvgWindSpeedMs()));
        }
        if (c.getAvgRelativeHumidityPct() != null) {
            sb.append(String.format(Locale.ROOT, "%nAverage relative humidity: %.0f%%", c.getAvgRelativeHumidityPct()));
        }
        return sb.toString();
    }

    private static String fmtDesign(ShelterDesign d) {
        StringBuilder sb = new StringBuilder();
        sb.append("Shape: ").append(d.getShape()).append(", Orientation: ").append(d.getOrientation());
        if (d.getFloorAreaM2() != null) {
            sb.append(String.format(Locale.ROOT, ", Floor area: %.1f m2", d.getFloorAreaM2()));
        }
        sb.append(String.format(Locale.ROOT, "%nWall: %s, %s mm thick%s", matName(d.getWallMaterial()), d.getWallThicknessMm(),
            d.getWallInsulationMaterial() != null
                ? String.format(Locale.ROOT, " + %s insulation, %s mm", matName(d.getWallInsulationMaterial()), d.getWallInsulationThicknessMm())
                : " (no separate insulation layer)"));
        sb.append(String.format(Locale.ROOT, "%nRoof: %s, %s mm thick%s", matName(d.getRoofMaterial()), d.getRoofThicknessMm(),
            d.getRoofInsulationMaterial() != null
                ? String.format(Locale.ROOT, " + %s insulation, %s mm", matName(d.getRoofInsulationMaterial()), d.getRoofInsulationThicknessMm())
                : " (no separate insulation layer)"));
        sb.append("\nThermal mass: ").append(d.getThermalMass() != null
            ? String.format(Locale.ROOT, "%s kg of %s (%s exposure)", d.getThermalMass().getMassKg(),
                matName(d.getThermalMass().getMaterial()), d.getThermalMass().getExposure())
            : "none configured");
        sb.append(String.format(Locale.ROOT, "%nOccupancy: %s person(s), %s activity level",
            d.getOccupancyCount(), d.getOccupancyActivity()));
        sb.append(String.format(Locale.ROOT, "%nAir leakage rate (ACH): %s", d.getAirLeakageAch()));
        return sb.toString();
    }

    private static String matName(Material m) {
        return m != null ? m.getName() : "unspecified";
    }

    private static String fmtResult(SimulationSummary s) {
        var u = s.uValues();
        var occ = s.occupancy();
        var daily = s.daily();
        var comfort = s.comfort();
        var scores = s.scores();
        return String.format(Locale.ROOT,
            "U-values (lower = better insulated): wall %.2f, roof %.2f, floor %.2f W/m2K%n"
                + "Predicted indoor temperature: %.1f to %.1f C (average %.1f C)%n"
                + "Comfortable duration: %.1f hours/day (daytime %.0f%%, night-time %.0f%%)%n"
                + "Daily solar heat gain: %.2f kWh%n"
                + "Daily heat loss -- wall %.2f, roof %.2f, floor %.2f, openings %.2f, ventilation %.2f kWh (total %.2f kWh)%n"
                + "Daily net energy balance: %.2f kWh (heating shortfall %.2f kWh, cooling excess %.2f kWh)%n"
                + "Occupancy: %d person(s) (%s) -- %.0f W sensible heat added%n"
                + "Scores (0-100): Comfort %.0f, Heat retention %.0f, Solar utilization %.0f -- Thermal Comfort Score %d",
            u.wall(), u.roof(), u.floor(),
            comfort.minIndoor(), comfort.maxIndoor(), comfort.avgIndoor(),
            comfort.comfortHoursPerDay(), comfort.dayComfortPct(), comfort.nightComfortPct(),
            daily.solarKwh(),
            daily.wallLossKwh(), daily.roofLossKwh(), daily.floorLossKwh(), daily.openingLossKwh(), daily.ventLossKwh(), daily.totalLossKwh(),
            daily.netKwh(), daily.heatingReqKwh(), daily.coolingReqKwh(),
            occ.persons(), occ.activityLabel(), occ.sensibleW(),
            scores.comfortScore(), scores.heatRetentionPct(), scores.solarUtilizationPct(), scores.thermalComfortScore()
        );
    }

    private static String nonBlankJoin(String... parts) {
        return Arrays.stream(parts).filter(p -> p != null && !p.isBlank()).collect(Collectors.joining(", "));
    }
}
