package net.deckserver.tournament.cli;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import net.deckserver.tournament.compare.ComparisonRunner;
import net.deckserver.tournament.domain.Player;
import net.deckserver.tournament.domain.TableSeat;
import net.deckserver.tournament.domain.TournamentSchedule;
import net.deckserver.tournament.solver.SeatLayoutCalculator;
import net.deckserver.tournament.solver.TournamentScheduleFactory;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Command-line entry point for the tournament seating solver.
 * Usage:
 * java -jar target/quarkus-app/quarkus-run.jar --players=10 --rounds=3
 * java -jar target/quarkus-app/quarkus-run.jar --players=10 --rounds=3 --time=120s
 * Arguments:
 * --players=N   Number of players (required, minimum 4)
 * --rounds=N    Number of rounds (required, typically 2 or 3)
 * --time=Xs     Total solver time budget, e.g. 60s, 2m (optional, default from application.properties)
 * All valid table layouts for the given player count are solved in parallel.
 * Each layout receives the full time budget. The best-scoring solution across
 * all layouts is printed as the final result.
 */
@QuarkusMain
public class TournamentCLI implements QuarkusApplication {

    @ConfigProperty(name = "quarkus.timefold.solver.termination.spent-limit", defaultValue = "60s")
    String defaultTimeLimit;

    @Override
    public int run(String... args) {
        if (args.length > 0 && "compare".equalsIgnoreCase(args[0])) {
            return runCompare(args);
        }

        // ── Parse arguments ────────────────────────────────────────────────────
        int playerCount = 0;
        int roundCount = 0;
        String timeLimit = null;

        for (String arg : args) {
            if (arg.startsWith("--players=")) {
                playerCount = Integer.parseInt(arg.substring("--players=".length()));
            } else if (arg.startsWith("--rounds=")) {
                roundCount = Integer.parseInt(arg.substring("--rounds=".length()));
            } else if (arg.startsWith("--time=")) {
                timeLimit = arg.substring("--time=".length());
            }
        }

        if (playerCount < 4) {
            System.err.println("Usage: --players=N --rounds=N [--time=Xs]");
            System.err.println("  --players must be >= 4");
            return 1;
        }
        if (roundCount < 1) {
            System.err.println("Usage: --players=N --rounds=N [--time=Xs]");
            System.err.println("  --rounds must be >= 1");
            return 1;
        }

        Duration totalBudget = parseDuration(timeLimit != null ? timeLimit : defaultTimeLimit);

        // ── Enumerate all valid layouts ────────────────────────────────────────
        List<SeatLayoutCalculator.TableLayout> layouts =
                SeatLayoutCalculator.allLayouts(playerCount);

        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.printf("  Tournament Seating Solver%n");
        System.out.printf("  Players          : %d%n", playerCount);
        System.out.printf("  Rounds           : %d%n", roundCount);
        System.out.printf("  Total time       : %s%n", formatDuration(totalBudget));
        System.out.printf("  Layouts to try   : %d (parallel, %s each)%n", layouts.size(), formatDuration(totalBudget));
        System.out.println("  Layouts:");
        for (int i = 0; i < layouts.size(); i++) {
            System.out.printf("    [%d] %s%n", i + 1, layouts.get(i));
        }
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        // ── Build solver config (each layout gets the full budget) ─────────────
        SolverConfig solverConfig = SolverConfig.createFromXmlResource("solverConfig.xml", TournamentCLI.class.getClassLoader());
        solverConfig.setTerminationConfig(new TerminationConfig().withSpentLimit(totalBudget));

        List<Player> players = IntStream.rangeClosed(1, playerCount)
                .mapToObj(i -> new Player("P" + i, "Player " + i))
                .collect(Collectors.toList());

        // ── Solve all layouts in parallel, track best ──────────────────────────
        ExecutorService executor = Executors.newFixedThreadPool(layouts.size());
        List<Future<TournamentSchedule>> futures = new ArrayList<>(layouts.size());

        for (int i = 0; i < layouts.size(); i++) {
            final int idx = i;
            SeatLayoutCalculator.TableLayout layout = layouts.get(i);
            TournamentSchedule problem = TournamentScheduleFactory.create(players, roundCount, layout);

            futures.add(executor.submit(() -> {
                SolverFactory<TournamentSchedule> factory = SolverFactory.create(solverConfig);
                var solver = factory.buildSolver();
                solver.addEventListener(event -> System.out.printf(
                        "  [%d/%d] [%5.1fs] score: %s%n",
                        idx + 1, layouts.size(),
                        event.getTimeMillisSpent() / 1000.0, event.getNewBestScore()));
                System.out.printf("%n  [%d/%d] Started: %s%n", idx + 1, layouts.size(), layout);
                return solver.solve(problem);
            }));
        }
        executor.shutdown();

        TournamentSchedule bestSolution = null;
        HardMediumSoftScore bestScore = null;

        for (int i = 0; i < futures.size(); i++) {
            TournamentSchedule solution;
            try {
                solution = futures.get(i).get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.printf("  Layout %d interrupted%n", i + 1);
                continue;
            } catch (ExecutionException e) {
                System.err.printf("  Layout %d failed: %s%n", i + 1, e.getCause().getMessage());
                continue;
            }
            HardMediumSoftScore score = solution.getScore();
            System.out.printf("  [%d/%d] Final score: %s (%s)%n",
                    i + 1, layouts.size(), score, layouts.get(i));
            if (bestScore == null || score.compareTo(bestScore) > 0) {
                bestScore = score;
                bestSolution = solution;
                System.out.printf("  [%d/%d] *** New best! ***%n", i + 1, layouts.size());
            }
        }

        // ── Print best result ──────────────────────────────────────────────────
        System.out.println();
        printSolution(bestSolution);
        return 0;
    }

    private int runCompare(String... args) {
        int playerCount = 0;
        int roundCount = 0;
        String timeLimit = null;
        Path archonPath = Path.of("thearchon1.5l.xlsx");
        Path krcgScriptPath = Path.of("tools/krcg/krcg_score.py");

        for (String arg : args) {
            if (arg.startsWith("--players=")) {
                playerCount = Integer.parseInt(arg.substring("--players=".length()));
            } else if (arg.startsWith("--rounds=")) {
                roundCount = Integer.parseInt(arg.substring("--rounds=".length()));
            } else if (arg.startsWith("--time=")) {
                timeLimit = arg.substring("--time=".length());
            } else if (arg.startsWith("--archon=")) {
                archonPath = Path.of(arg.substring("--archon=".length()));
            } else if (arg.startsWith("--krcg-script=")) {
                krcgScriptPath = Path.of(arg.substring("--krcg-script=".length()));
            }
        }

        if (playerCount < 4 || (roundCount != 2 && roundCount != 3)) {
            System.err.println("Usage: compare --players=N --rounds=2|3 [--time=Xs] " +
                    "[--archon=thearchon1.5l.xlsx] [--krcg-script=tools/krcg/krcg_score.py]");
            return 1;
        }

        Duration totalBudget = parseDuration(timeLimit != null ? timeLimit : defaultTimeLimit);
        return new ComparisonRunner().run(playerCount, roundCount, totalBudget, archonPath, krcgScriptPath);
    }

    private void printSolution(TournamentSchedule solution) {
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.printf("  BEST SOLUTION  (score: %s)%n", solution.getScore());
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        // Determine which players sit out each round
        java.util.Set<String> allPlayerIds = solution.getPlayers().stream()
                .map(Player::getId).collect(Collectors.toSet());

        solution.getSeats().stream()
                .collect(Collectors.groupingBy(
                        s -> s.getRound().getRoundNumber(),
                        java.util.TreeMap::new,
                        Collectors.groupingBy(
                                TableSeat::getTableNumber,
                                java.util.TreeMap::new,
                                Collectors.toList())))
                .forEach((roundNum, tables) -> {
                    // Find sitting-out players for this round
                    java.util.Set<String> seatedIds = tables.values().stream()
                            .flatMap(List::stream)
                            .filter(s -> s.getPlayer() != null)
                            .map(s -> s.getPlayer().getId())
                            .collect(Collectors.toSet());
                    List<String> sittingOut = solution.getPlayers().stream()
                            .filter(p -> !seatedIds.contains(p.getId()))
                            .map(Player::getName)
                            .sorted()
                            .collect(Collectors.toList());

                    System.out.printf("%n  Round %d:%n", roundNum);
                    if (!sittingOut.isEmpty()) {
                        System.out.printf("    Sitting out: %s%n", String.join(", ", sittingOut));
                    }

                    tables.forEach((tableNum, seats) -> {
                        seats.sort(Comparator.comparingInt(TableSeat::getSeatPosition));
                        System.out.printf("    Table %d (%d seats):%n", tableNum, seats.get(0).getTableSize());
                        seats.forEach(seat -> System.out.printf(
                                "      Seat %d [transfer=%d]: %s%n",
                                seat.getSeatPosition(),
                                seat.getStartingTransferValue(),
                                seat.getPlayer() != null ? seat.getPlayer().getName() : "(empty)"));
                    });
                });

        System.out.println();
        System.out.println("  Predator → Prey relationships:");
        solution.getSeats().stream()
                .filter(s -> s.getPlayer() != null)
                .collect(Collectors.groupingBy(
                        s -> s.getRound().getRoundNumber(),
                        java.util.TreeMap::new,
                        Collectors.groupingBy(
                                TableSeat::getTableNumber,
                                java.util.TreeMap::new,
                                Collectors.toList())))
                .forEach((roundNum, tables) -> {
                    System.out.printf("    Round %d:%n", roundNum);
                    tables.forEach((tableNum, seats) -> {
                        seats.sort(Comparator.comparingInt(TableSeat::getSeatPosition));
                        List<String> names = seats.stream()
                                .map(s -> s.getPlayer().getName())
                                .collect(Collectors.toList());
                        System.out.printf("      Table %d: %s → %s%n",
                                tableNum,
                                String.join(" → ", names),
                                names.get(0));
                    });
                });

        System.out.println();
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    private Duration parseDuration(String input) {
        input = input.trim().toLowerCase();
        if (input.startsWith("pt")) return Duration.parse(input.toUpperCase());
        if (input.endsWith("s")) return Duration.ofSeconds(Long.parseLong(input.substring(0, input.length() - 1)));
        if (input.endsWith("m")) return Duration.ofMinutes(Long.parseLong(input.substring(0, input.length() - 1)));
        if (input.endsWith("h")) return Duration.ofHours(Long.parseLong(input.substring(0, input.length() - 1)));
        return Duration.ofSeconds(Long.parseLong(input));
    }

    private String formatDuration(Duration d) {
        long s = d.getSeconds();
        if (s >= 3600) return (s / 3600) + "h" + ((s % 3600) / 60) + "m";
        if (s >= 60) return (s / 60) + "m" + (s % 60) + "s";
        return s + "s";
    }
}
