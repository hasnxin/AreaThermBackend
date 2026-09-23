package com.areatherm.optimizationrun;

import com.areatherm.climate.ClimateProfile;
import com.areatherm.climate.ClimateProfileRepository;
import com.areatherm.climate.ClimateProfileService;
import com.areatherm.design.ShelterDesign;
import com.areatherm.design.ShelterDesignRepository;
import com.areatherm.design.ShelterDesignService;
import com.areatherm.material.MaterialLibraryService;
import com.areatherm.ml.SurrogatePredictor;
import com.areatherm.optimization.OptimizationEngine;
import com.areatherm.optimization.model.MaterialCatalog;
import com.areatherm.optimization.model.OptimizationResult;
import com.areatherm.optimization.model.Weights;
import com.areatherm.project.Project;
import com.areatherm.project.ProjectRepository;
import com.areatherm.thermal.model.Design;
import com.areatherm.thermal.model.SimConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Orchestrates an optimization run: loads the base design/climate profile,
 * builds the resolved MaterialCatalog OptimizationEngine's 567-candidate
 * search needs, runs it, and persists every evaluated candidate as a real
 * ShelterDesign + DesignCandidate row (design_candidate.shelter_design_id
 * is NOT NULL -- see DesignCandidate's javadoc for why most never get a
 * full Simulation too).
 */
@Service
public class OptimizationRunService {

    private static final Logger log = LoggerFactory.getLogger(OptimizationRunService.class);
    private static final String[] TOP_LABELS = {"A", "B", "C", "D", "E"};

    private final OptimizationRunRepository optimizationRunRepository;
    private final DesignCandidateRepository designCandidateRepository;
    private final ShelterDesignRepository shelterDesignRepository;
    private final ClimateProfileRepository climateProfileRepository;
    private final ProjectRepository projectRepository;
    private final ShelterDesignService shelterDesignService;
    private final ClimateProfileService climateProfileService;
    private final MaterialLibraryService materialLibraryService;
    private final SurrogatePredictor surrogatePredictor;

    public OptimizationRunService(OptimizationRunRepository optimizationRunRepository,
                                   DesignCandidateRepository designCandidateRepository,
                                   ShelterDesignRepository shelterDesignRepository,
                                   ClimateProfileRepository climateProfileRepository,
                                   ProjectRepository projectRepository,
                                   ShelterDesignService shelterDesignService,
                                   ClimateProfileService climateProfileService,
                                   MaterialLibraryService materialLibraryService,
                                   SurrogatePredictor surrogatePredictor) {
        this.optimizationRunRepository = optimizationRunRepository;
        this.designCandidateRepository = designCandidateRepository;
        this.shelterDesignRepository = shelterDesignRepository;
        this.climateProfileRepository = climateProfileRepository;
        this.projectRepository = projectRepository;
        this.shelterDesignService = shelterDesignService;
        this.climateProfileService = climateProfileService;
        this.materialLibraryService = materialLibraryService;
        this.surrogatePredictor = surrogatePredictor;
    }

    @Transactional
    public OptimizationRun createQueued(Long projectId, Long baseShelterDesignId, Long climateProfileId,
                                         int timeStepMinutes, SimConfig.PeriodType periodType,
                                         Weights weights, boolean broaderSearchRequested) {
        Project project = projectRepository.findById(projectId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown project: " + projectId));
        ShelterDesign baseDesign = shelterDesignRepository.findById(baseShelterDesignId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown shelter design: " + baseShelterDesignId));
        ClimateProfile climateProfile = climateProfileRepository.findById(climateProfileId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown climate profile: " + climateProfileId));

        if (broaderSearchRequested) {
            // Accepted, not rejected -- the ml/ package's surrogate is a
            // deliberate stub this round (see SurrogatePredictor.isAvailable()),
            // so every request falls back to the deterministic grid search,
            // exactly like the frontend's own opts.broaderSearch does when
            // window.APP_ML.isAvailable() is false. usedMlScreening stays
            // FALSE regardless of what was requested.
            log.info("broaderSearch requested for project {} but no surrogate is configured -- using the grid search", projectId);
        }

        OptimizationRun run = new OptimizationRun();
        run.setProject(project);
        run.setBaseShelterDesign(baseDesign);
        run.setClimateProfile(climateProfile);
        run.setTimeStepMinutes(timeStepMinutes);
        run.setPeriodType(periodType);
        run.setWeightComfort(BigDecimal.valueOf(weights.comfort()));
        run.setWeightRetention(BigDecimal.valueOf(weights.retention()));
        run.setWeightSolar(BigDecimal.valueOf(weights.solar()));
        run.setWeightEnergy(BigDecimal.valueOf(weights.energy()));
        run.setWeightCost(BigDecimal.valueOf(weights.cost()));
        run.setUsedMlScreening(false);
        run.setStatus(OptimizationRun.Status.QUEUED);
        return optimizationRunRepository.save(run);
    }

    // See SimulationService.runAsync's javadoc for why this whole method
    // needs one ambient transaction: it runs on its own @Async thread, and
    // without one, every lazy association (design materials, opening
    // glazing, etc.) traversed after the first repository call has already
    // returned would fail with LazyInitializationException.
    @Async("engineTaskExecutor")
    @Transactional
    public void runAsync(Long optimizationRunId) {
        OptimizationRun run = optimizationRunRepository.findById(optimizationRunId).orElse(null);
        if (run == null) {
            log.warn("runAsync: optimization run {} no longer exists", optimizationRunId);
            return;
        }
        run.setStatus(OptimizationRun.Status.RUNNING);
        optimizationRunRepository.save(run);

        try {
            Design baseDesign = shelterDesignService.toDesign(run.getBaseShelterDesign());
            var season = climateProfileService.toSeason(run.getClimateProfile());
            // No start/end window on optimization_run (unlike simulation) --
            // days is derived from periodType alone; SEASONAL uses a
            // documented ~3-month approximation, CUSTOM defaults to 1 in the
            // absence of any other window to derive it from.
            int days = switch (run.getPeriodType()) {
                case TWENTY_FOUR_HOUR -> 1;
                case SEVEN_DAY -> 7;
                case THIRTY_DAY -> 30;
                case SEASONAL -> 90;
                case CUSTOM -> 1;
            };
            SimConfig simConfig = new SimConfig(run.getTimeStepMinutes(), run.getPeriodType(), days);
            Weights weights = new Weights(
                run.getWeightComfort().doubleValue(), run.getWeightRetention().doubleValue(),
                run.getWeightSolar().doubleValue(), run.getWeightEnergy().doubleValue(), run.getWeightCost().doubleValue()
            );
            MaterialCatalog catalog = materialLibraryService.buildCatalog();

            OptimizationResult result = OptimizationEngine.runOptimization(
                baseDesign, season, simConfig, weights, catalog, surrogatePredictor.isAvailable()
            );

            persistCandidates(run, result);

            run.setCandidatesEvaluated(result.candidatesEvaluated());
            run.setStatus(OptimizationRun.Status.COMPLETE);
            optimizationRunRepository.save(run);
        } catch (Exception e) {
            log.error("Optimization run {} failed", optimizationRunId, e);
            run.setStatus(OptimizationRun.Status.FAILED);
            optimizationRunRepository.save(run);
        }
    }

    @Transactional
    protected void persistCandidates(OptimizationRun run, OptimizationResult result) {
        List<OptimizationResult.ScoredCandidate> all = result.all();
        for (int i = 0; i < all.size(); i++) {
            OptimizationResult.ScoredCandidate c = all.get(i);
            ShelterDesign persistedDesign = shelterDesignService.persistFromDesign(
                c.design(), run.getBaseShelterDesign().getName() + " (candidate " + c.rank() + ")",
                run.getProject(), run.getBaseShelterDesign().getComfortProfile()
            );

            DesignCandidate dc = new DesignCandidate();
            dc.setOptimizationRun(run);
            dc.setLabel(c.label() != null ? c.label() : String.valueOf(c.rank()));
            dc.setShelterDesign(persistedDesign);
            dc.setComfortScore(BigDecimal.valueOf(c.score().comfort()));
            dc.setRetentionScore(BigDecimal.valueOf(c.score().retention()));
            dc.setSolarScore(BigDecimal.valueOf(c.score().solar()));
            dc.setEnergyScore(BigDecimal.valueOf(c.score().energyScore()));
            dc.setCostScore(BigDecimal.valueOf(c.score().costScore()));
            dc.setWeightedTotalScore(BigDecimal.valueOf(c.score().total()));
            dc.setEstimatedCostInr(BigDecimal.valueOf(c.cost()));
            dc.setRecommended(c.isRecommended());
            designCandidateRepository.save(dc);
        }
    }

    public Optional<OptimizationRun> findById(Long id) {
        return optimizationRunRepository.findById(id);
    }

    public List<DesignCandidate> getCandidates(Long optimizationRunId) {
        return designCandidateRepository.findByOptimizationRunIdOrderByLabelAsc(optimizationRunId);
    }
}
