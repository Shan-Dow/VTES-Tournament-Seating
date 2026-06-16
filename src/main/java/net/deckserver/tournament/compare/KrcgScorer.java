package net.deckserver.tournament.compare;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public class KrcgScorer {

    private final Path scriptPath;
    private final ObjectMapper objectMapper;

    public KrcgScorer(Path scriptPath) {
        this.scriptPath = scriptPath;
        this.objectMapper = new ObjectMapper();
    }

    public KrcgScore score(SeatingPlan plan) throws IOException, InterruptedException {
        Path input = Files.createTempFile("seating-plan-", ".json");
        try {
            objectMapper.writeValue(input.toFile(), toJson(plan));
            Process process = new ProcessBuilder("python3", scriptPath.toString(), input.toString())
                    .redirectErrorStream(true)
                    .start();
            boolean completed = process.waitFor(Duration.ofSeconds(30).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            String output = new String(process.getInputStream().readAllBytes());
            if (!completed) {
                process.destroyForcibly();
                throw new IOException("KRCG scorer timed out. Output:\n" + output);
            }
            if (process.exitValue() != 0) {
                throw new IOException("KRCG scorer failed with exit " + process.exitValue() + ". Output:\n" + output);
            }
            return objectMapper.readValue(output, KrcgScore.class);
        } finally {
            Files.deleteIfExists(input);
        }
    }

    private Map<String, Object> toJson(SeatingPlan plan) {
        List<List<List<Integer>>> rounds = plan.rounds().stream()
                .map(round -> round.tables().stream()
                        .map(SeatingTable::playerNumbers)
                        .toList())
                .toList();
        return Map.of(
                "playerCount", plan.playerCount(),
                "rounds", rounds,
                "source", plan.source());
    }
}
