package net.deckserver.tournament.compare;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import net.deckserver.tournament.domain.Player;
import net.deckserver.tournament.domain.TournamentSchedule;
import net.deckserver.tournament.solver.SeatLayoutCalculator;
import net.deckserver.tournament.solver.TournamentScheduleFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class ComparisonRunner {

    private final ArchonXlsxImporter archonImporter = new ArchonXlsxImporter();
    private final TimefoldPlanMapper mapper = new TimefoldPlanMapper();

    public int run(int playerCount, int preliminaryRounds, Duration timeBudget,
                   Path archonPath, Path krcgScriptPath) {
        try {
            printReport(compare(playerCount, preliminaryRounds, timeBudget, archonPath, krcgScriptPath));
            return 0;
        } catch (Exception e) {
            System.err.println("Comparison failed: " + e.getMessage());
            e.printStackTrace(System.err);
            return 1;
        }
    }

    public ComparisonResult compare(int playerCount, int preliminaryRounds, Duration timeBudget,
                                    Path archonPath, Path krcgScriptPath)
            throws Exception {
        SolverConfig solverConfig = SolverConfig.createFromXmlResource(
                "solverConfig.xml", ComparisonRunner.class.getClassLoader());
        solverConfig.setTerminationConfig(new TerminationConfig().withSpentLimit(timeBudget));

        SeatingPlan archonPlan = archonImporter.importPlan(archonPath, playerCount, preliminaryRounds);
        KrcgScorer krcgScorer = new KrcgScorer(krcgScriptPath);
        TimefoldPlanScorer timefoldScorer = new TimefoldPlanScorer(solverConfig);

        KrcgScore archonKrcg = krcgScorer.score(archonPlan);
        TimefoldScoreSummary archonTimefold = timefoldScorer.score(archonPlan);

        int requiredSeatedPlayers = archonPlan.rounds().getFirst().tables().stream()
                .mapToInt(table -> table.playerNumbers().size())
                .sum();
        TournamentSchedule bestSchedule = solveBestExact(playerCount, preliminaryRounds,
                requiredSeatedPlayers, solverConfig);
        SeatingPlan timefoldPlan = mapper.fromSchedule(bestSchedule, "Timefold generated");
        KrcgScore timefoldKrcg = krcgScorer.score(timefoldPlan);
        TimefoldScoreSummary timefoldTimefold = timefoldScorer.score(timefoldPlan);

        return new ComparisonResult(playerCount, preliminaryRounds, timeBudget,
                archonPlan, archonKrcg, archonTimefold,
                timefoldPlan, timefoldKrcg, timefoldTimefold);
    }

    private TournamentSchedule solveBestExact(int playerCount, int preliminaryRounds,
                                              int requiredSeatedPlayers, SolverConfig solverConfig)
            throws InterruptedException {
        List<SeatLayoutCalculator.TableLayout> layouts = SeatLayoutCalculator.allLayouts(playerCount).stream()
                .filter(layout -> layout.seatedPlayers() == requiredSeatedPlayers)
                .toList();
        if (layouts.isEmpty()) {
            throw new IllegalStateException("No Timefold layout matches Archon's seated player count per round: "
                    + requiredSeatedPlayers);
        }
        List<Player> players = IntStream.rangeClosed(1, playerCount)
                .mapToObj(i -> new Player("P" + i, "Player " + i))
                .collect(Collectors.toList());

        ExecutorService executor = Executors.newFixedThreadPool(layouts.size());
        List<Future<TournamentSchedule>> futures = new ArrayList<>(layouts.size());
        for (SeatLayoutCalculator.TableLayout layout : layouts) {
            TournamentSchedule problem = TournamentScheduleFactory.createExact(players, preliminaryRounds, layout);
            futures.add(executor.submit(() -> {
                SolverFactory<TournamentSchedule> factory = SolverFactory.create(solverConfig);
                return factory.buildSolver().solve(problem);
            }));
        }
        executor.shutdown();

        TournamentSchedule best = null;
        HardMediumSoftScore bestScore = null;
        for (int i = 0; i < futures.size(); i++) {
            try {
                TournamentSchedule solution = futures.get(i).get();
                if (bestScore == null || solution.getScore().compareTo(bestScore) > 0) {
                    bestScore = solution.getScore();
                    best = solution;
                }
                System.out.printf("  candidate layout %d/%d score: %s (%s)%n",
                        i + 1, futures.size(), solution.getScore(), layouts.get(i));
            } catch (ExecutionException e) {
                System.err.printf("  candidate layout %d/%d failed: %s%n",
                        i + 1, futures.size(), e.getCause().getMessage());
            }
        }
        if (best == null) {
            throw new IllegalStateException("No Timefold candidate was solved.");
        }
        return best;
    }

    public void printReport(ComparisonResult result) {
        System.out.println();
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.printf("  Seating Comparison: %d players, %d preliminary rounds, %ss%n",
                result.playerCount(), result.preliminaryRounds(), result.timeBudget().toSeconds());
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.printf("  Archon source       : %s%n", result.archonPlan().source());
        System.out.printf("  Timefold source     : %s%n", result.timefoldPlan().source());
        System.out.println();
        System.out.printf("  %-18s %-22s %-22s%n", "Plan", "KRCG total", "Timefold score");
        System.out.printf("  %-18s %-22.4f %-22s%n", "Archon", result.archonKrcg().total(), result.archonTimefold().score());
        System.out.printf("  %-18s %-22.4f %-22s%n", "Timefold", result.timefoldKrcg().total(), result.timefoldTimefold().score());
        System.out.println();
        System.out.printf("  KRCG delta          : %.4f (%s)%n",
                result.krcgDelta(),
                result.krcgComparison() < 0 ? "Timefold better" : result.krcgComparison() > 0 ? "Archon better" : "tie");
        System.out.printf("  Timefold verdict    : %s%n",
                result.timefoldComparison() > 0 ? "Timefold better" : result.timefoldComparison() < 0 ? "Archon better" : "tie");
        System.out.printf("  Confirmed superior  : %s%n",
                result.confirmedSuperior() ? "yes" : "no");
        System.out.println();
        System.out.println("  KRCG rule values:");
        for (String rule : List.of("R1", "R2", "R3", "R4", "R5", "R6", "R7", "R8", "R9")) {
            System.out.printf("    %s  Archon=%-12.4f Timefold=%-12.4f Delta=% .4f%n",
                    rule,
                    result.archonKrcg().rules().getOrDefault(rule, 0.0),
                    result.timefoldKrcg().rules().getOrDefault(rule, 0.0),
                    result.timefoldKrcg().rules().getOrDefault(rule, 0.0)
                            - result.archonKrcg().rules().getOrDefault(rule, 0.0));
        }
        System.out.println();
        printSeatingPlan("Archon Seating", result.archonPlan());
        System.out.println();
        printSeatingPlan("Timefold Seating", result.timefoldPlan());
        System.out.println();
        printPlayerSummary(result);
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    private void printSeatingPlan(String title, SeatingPlan plan) {
        System.out.printf("  %s:%n", title);
        for (SeatingRound round : plan.rounds()) {
            System.out.printf("    Round %d:%n", round.roundNumber());
            if (!round.sittingOut().isEmpty()) {
                System.out.printf("      Sitting out: %s%n",
                        round.sittingOut().stream()
                                .map(String::valueOf)
                                .collect(Collectors.joining(", ")));
            }
            for (SeatingTable table : round.tables()) {
                System.out.printf("      Table %d (%d): %s%n",
                        table.tableNumber(),
                        table.playerNumbers().size(),
                        table.playerNumbers().stream()
                                .map(String::valueOf)
                                .collect(Collectors.joining(" -> ")));
            }
        }
    }

    private void printPlayerSummary(ComparisonResult result) {
        System.out.println("  Player Summary:");
        System.out.printf("    %-6s %-8s %-8s %-8s %-8s %-8s %-8s%n",
                "Player", "A-VPs", "T-VPs", "VP Δ", "A-Xfer", "T-Xfer", "Xfer Δ");
        for (ComparisonResult.PlayerSummaryRow row : result.playerSummary()) {
            System.out.printf("    %-6d %-8.3f %-8.3f %-8.3f %-8.3f %-8.3f %-8.3f%n",
                    row.player(),
                    row.archonAverageAvailableVps(),
                    row.timefoldAverageAvailableVps(),
                    row.timefoldAverageAvailableVps() - row.archonAverageAvailableVps(),
                    row.archonAverageStartingTransfers(),
                    row.timefoldAverageStartingTransfers(),
                    row.timefoldAverageStartingTransfers() - row.archonAverageStartingTransfers());
        }
    }
}
