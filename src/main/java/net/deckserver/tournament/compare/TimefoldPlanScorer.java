package net.deckserver.tournament.compare;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import ai.timefold.solver.core.api.solver.SolutionManager;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.SolverConfig;
import net.deckserver.tournament.domain.TournamentSchedule;

public class TimefoldPlanScorer {

    private final SolutionManager<TournamentSchedule, HardMediumSoftScore> solutionManager;
    private final TimefoldPlanMapper mapper = new TimefoldPlanMapper();

    public TimefoldPlanScorer(SolverConfig solverConfig) {
        SolverFactory<TournamentSchedule> solverFactory = SolverFactory.create(solverConfig);
        this.solutionManager = SolutionManager.create(solverFactory);
    }

    public TimefoldScoreSummary score(SeatingPlan plan) {
        mapper.validate(plan);
        TournamentSchedule schedule = mapper.toSchedule(plan);
        HardMediumSoftScore score = solutionManager.update(schedule);
        return new TimefoldScoreSummary(score, "Constraint analysis is not available in Timefold Community Edition.");
    }
}
