package net.deckserver.tournament.compare;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class BatchComparisonRunner {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private final ComparisonRunner comparisonRunner = new ComparisonRunner();

    public int run(BatchOptions options) {
        try {
            BatchComparisonState state = loadOrCreateState(options);
            int cycle = 0;
            while (options.cycles() == 0 || cycle < options.cycles()) {
                cycle++;
                List<BatchComparisonCase> pending = state.cases.stream()
                        .filter(item -> !item.confirmedSuperior)
                        .toList();
                if (pending.isEmpty()) {
                    System.out.println("All batch comparison cases are confirmed superior.");
                    break;
                }

                System.out.printf("Batch cycle %d: %d pending case(s).%n", cycle, pending.size());
                for (BatchComparisonCase item : pending) {
                    runCase(item, options);
                    state.updatedAt = Instant.now().toString();
                    saveState(options.statePath(), state);
                    writeReport(options.reportPath(), state);
                }
            }
            saveState(options.statePath(), state);
            writeReport(options.reportPath(), state);
            printSummary(state, options);
            return 0;
        } catch (Exception e) {
            System.err.println("Batch comparison failed: " + e.getMessage());
            e.printStackTrace(System.err);
            return 1;
        }
    }

    private void runCase(BatchComparisonCase item, BatchOptions options) throws Exception {
        Duration budget = Duration.ofSeconds(item.nextTimeSeconds);
        System.out.printf("%nComparing %d players, %d rounds, attempt %d, budget %s...%n",
                item.playerCount, item.preliminaryRounds, item.attempts + 1, formatDuration(budget));
        ComparisonResult result = comparisonRunner.compare(
                item.playerCount,
                item.preliminaryRounds,
                budget,
                options.archonPath(),
                options.krcgScriptPath());

        item.attempts++;
        item.lastRunAt = Instant.now().toString();
        item.archonTimefoldScore = result.archonTimefold().score().toString();
        item.timefoldTimefoldScore = result.timefoldTimefold().score().toString();
        item.archonKrcgTotal = result.archonKrcg().total();
        item.timefoldKrcgTotal = result.timefoldKrcg().total();
        item.krcgDelta = result.krcgDelta();
        item.archonKrcgRules = new java.util.LinkedHashMap<>(result.archonKrcg().rules());
        item.timefoldKrcgRules = new java.util.LinkedHashMap<>(result.timefoldKrcg().rules());
        item.archonPlan = result.archonPlan();
        item.timefoldPlan = result.timefoldPlan();

        if (result.confirmedSuperior()) {
            item.confirmedSuperior = true;
            item.confirmedAt = Instant.now().toString();
            System.out.printf("Confirmed superior: %d players, %d rounds at %s.%n",
                    item.playerCount, item.preliminaryRounds, formatDuration(budget));
        } else {
            item.nextTimeSeconds += options.step().toSeconds();
            System.out.printf("Not confirmed. Next budget: %s.%n",
                    formatDuration(Duration.ofSeconds(item.nextTimeSeconds)));
        }
    }

    private BatchComparisonState loadOrCreateState(BatchOptions options) throws IOException {
        if (Files.exists(options.statePath())) {
            return objectMapper.readValue(options.statePath().toFile(), BatchComparisonState.class);
        }

        BatchComparisonState state = new BatchComparisonState();
        state.minPlayers = options.minPlayers();
        state.maxPlayers = options.maxPlayers();
        state.rounds = new ArrayList<>(options.rounds());
        state.stepSeconds = options.step().toSeconds();
        for (int players = options.minPlayers(); players <= options.maxPlayers(); players++) {
            for (int rounds : options.rounds()) {
                BatchComparisonCase item = new BatchComparisonCase();
                item.playerCount = players;
                item.preliminaryRounds = rounds;
                item.nextTimeSeconds = options.initialTime().toSeconds();
                state.cases.add(item);
            }
        }
        saveState(options.statePath(), state);
        return state;
    }

    private void saveState(Path path, BatchComparisonState state) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        objectMapper.writeValue(path.toFile(), state);
    }

    private void writeReport(Path path, BatchComparisonState state) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        StringBuilder out = new StringBuilder();
        out.append("# Confirmed Superior Seating Report\n\n");
        out.append("- Updated: ").append(state.updatedAt).append('\n');
        out.append("- Player range: ").append(state.minPlayers).append("-").append(state.maxPlayers).append('\n');
        out.append("- Rounds: ").append(state.rounds).append('\n');
        out.append("- Step seconds: ").append(state.stepSeconds).append("\n\n");

        List<BatchComparisonCase> confirmed = state.cases.stream()
                .filter(item -> item.confirmedSuperior)
                .toList();
        out.append("Confirmed cases: ").append(confirmed.size()).append(" / ").append(state.cases.size()).append("\n\n");
        for (BatchComparisonCase item : confirmed) {
            appendCaseReport(out, item);
        }
        Files.writeString(path, out.toString());
    }

    private void appendCaseReport(StringBuilder out, BatchComparisonCase item) {
        out.append("## ").append(item.playerCount).append(" players, ")
                .append(item.preliminaryRounds).append(" rounds\n\n");
        out.append("- Attempts: ").append(item.attempts).append('\n');
        out.append("- Confirmed at: ").append(item.confirmedAt).append('\n');
        out.append("- Timefold score: ").append(item.timefoldTimefoldScore).append('\n');
        out.append("- Archon score: ").append(item.archonTimefoldScore).append('\n');
        out.append("- KRCG Timefold total: ").append(String.format("%.4f", item.timefoldKrcgTotal)).append('\n');
        out.append("- KRCG Archon total: ").append(String.format("%.4f", item.archonKrcgTotal)).append('\n');
        out.append("- KRCG delta: ").append(String.format("%.4f", item.krcgDelta)).append("\n\n");

        out.append("### KRCG Rules\n\n");
        out.append("| Rule | Archon | Timefold | Delta |\n");
        out.append("| --- | ---: | ---: | ---: |\n");
        for (String rule : List.of("R1", "R2", "R3", "R4", "R5", "R6", "R7", "R8", "R9")) {
            double archon = item.archonKrcgRules.getOrDefault(rule, 0.0);
            double timefold = item.timefoldKrcgRules.getOrDefault(rule, 0.0);
            out.append("| ").append(rule)
                    .append(" | ").append(String.format("%.4f", archon))
                    .append(" | ").append(String.format("%.4f", timefold))
                    .append(" | ").append(String.format("%.4f", timefold - archon))
                    .append(" |\n");
        }

        out.append("\n### Archon Seating\n\n```text\n")
                .append(ComparisonResult.seatingPlanText(item.archonPlan))
                .append("```\n\n");
        out.append("### Timefold Seating\n\n```text\n")
                .append(ComparisonResult.seatingPlanText(item.timefoldPlan))
                .append("```\n\n");
    }

    private void printSummary(BatchComparisonState state, BatchOptions options) {
        long confirmed = state.cases.stream().filter(item -> item.confirmedSuperior).count();
        long pending = state.cases.size() - confirmed;
        System.out.printf("%nBatch complete. Confirmed: %d. Pending: %d.%n", confirmed, pending);
        System.out.printf("State: %s%n", options.statePath());
        System.out.printf("Report: %s%n", options.reportPath());
        state.cases.stream()
                .filter(item -> !item.confirmedSuperior)
                .forEach(item -> System.out.printf("  pending %d players/%d rounds: attempts=%d next=%s%n",
                        item.playerCount,
                        item.preliminaryRounds,
                        item.attempts,
                        formatDuration(Duration.ofSeconds(item.nextTimeSeconds))));
    }

    private static String formatDuration(Duration duration) {
        long seconds = duration.toSeconds();
        if (seconds % 3600 == 0 && seconds >= 3600) {
            return (seconds / 3600) + "h";
        }
        if (seconds % 60 == 0 && seconds >= 60) {
            return (seconds / 60) + "m";
        }
        return seconds + "s";
    }

    public record BatchOptions(
            int minPlayers,
            int maxPlayers,
            Set<Integer> rounds,
            Duration initialTime,
            Duration step,
            int cycles,
            Path statePath,
            Path reportPath,
            Path archonPath,
            Path krcgScriptPath) {
    }
}
