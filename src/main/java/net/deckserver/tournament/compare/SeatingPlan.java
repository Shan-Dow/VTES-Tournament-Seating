package net.deckserver.tournament.compare;

import java.util.List;

public record SeatingPlan(
        int playerCount,
        int requestedRounds,
        List<SeatingRound> rounds,
        String source) {
}
