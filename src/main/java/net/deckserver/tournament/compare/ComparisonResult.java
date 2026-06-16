package net.deckserver.tournament.compare;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

public record ComparisonResult(
        int playerCount,
        int preliminaryRounds,
        Duration timeBudget,
        SeatingPlan archonPlan,
        KrcgScore archonKrcg,
        TimefoldScoreSummary archonTimefold,
        SeatingPlan timefoldPlan,
        KrcgScore timefoldKrcg,
        TimefoldScoreSummary timefoldTimefold) {

    public int timefoldComparison() {
        return timefoldTimefold.score().compareTo(archonTimefold.score());
    }

    public int krcgComparison() {
        return Double.compare(timefoldKrcg.total(), archonKrcg.total());
    }

    public boolean confirmedSuperior() {
        return timefoldComparison() > 0 && krcgComparison() < 0;
    }

    public double krcgDelta() {
        return timefoldKrcg.total() - archonKrcg.total();
    }

    public List<PlayerSummaryRow> playerSummary() {
        List<PlayerMetrics> archonMetrics = playerMetrics(archonPlan);
        List<PlayerMetrics> timefoldMetrics = playerMetrics(timefoldPlan);
        return java.util.stream.IntStream.rangeClosed(1, playerCount)
                .mapToObj(player -> new PlayerSummaryRow(
                        player,
                        archonMetrics.get(player).averageAvailableVps(),
                        timefoldMetrics.get(player).averageAvailableVps(),
                        archonMetrics.get(player).averageStartingTransfers(),
                        timefoldMetrics.get(player).averageStartingTransfers()))
                .toList();
    }

    private static List<PlayerMetrics> playerMetrics(SeatingPlan plan) {
        List<PlayerMetricsAccumulator> accumulators = new java.util.ArrayList<>();
        for (int i = 0; i <= plan.playerCount(); i++) {
            accumulators.add(new PlayerMetricsAccumulator());
        }

        for (SeatingRound round : plan.rounds()) {
            for (SeatingTable table : round.tables()) {
                int tableSize = table.playerNumbers().size();
                for (int seatIndex = 0; seatIndex < table.playerNumbers().size(); seatIndex++) {
                    int player = table.playerNumbers().get(seatIndex);
                    accumulators.get(player).add(tableSize, Math.min(seatIndex + 1, 4));
                }
            }
        }

        List<PlayerMetrics> metrics = new java.util.ArrayList<>();
        for (int player = 0; player <= plan.playerCount(); player++) {
            metrics.add(accumulators.get(player).toMetrics());
        }
        return metrics;
    }

    public static String seatingPlanText(SeatingPlan plan) {
        StringBuilder out = new StringBuilder();
        for (SeatingRound round : plan.rounds()) {
            out.append("Round ").append(round.roundNumber()).append('\n');
            if (!round.sittingOut().isEmpty()) {
                out.append("  Sitting out: ")
                        .append(round.sittingOut().stream()
                                .map(String::valueOf)
                                .collect(Collectors.joining(", ")))
                        .append('\n');
            }
            for (SeatingTable table : round.tables()) {
                out.append("  Table ").append(table.tableNumber())
                        .append(" (").append(table.playerNumbers().size()).append("): ")
                        .append(table.playerNumbers().stream()
                                .map(String::valueOf)
                                .collect(Collectors.joining(" -> ")))
                        .append('\n');
            }
        }
        return out.toString();
    }

    private static class PlayerMetricsAccumulator {
        private int playedRounds;
        private int availableVps;
        private int startingTransfers;

        void add(int tableSize, int startingTransfer) {
            playedRounds++;
            availableVps += tableSize;
            startingTransfers += startingTransfer;
        }

        PlayerMetrics toMetrics() {
            if (playedRounds == 0) {
                return new PlayerMetrics(0.0, 0.0);
            }
            return new PlayerMetrics(
                    (double) availableVps / playedRounds,
                    (double) startingTransfers / playedRounds);
        }
    }

    private record PlayerMetrics(double averageAvailableVps, double averageStartingTransfers) {
    }

    public record PlayerSummaryRow(
            int player,
            double archonAverageAvailableVps,
            double timefoldAverageAvailableVps,
            double archonAverageStartingTransfers,
            double timefoldAverageStartingTransfers) {
    }
}
