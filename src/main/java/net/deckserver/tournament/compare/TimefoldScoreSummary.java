package net.deckserver.tournament.compare;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;

public record TimefoldScoreSummary(
        HardMediumSoftScore score,
        String analysis) {
}
