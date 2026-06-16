package net.deckserver.tournament.compare;

import java.util.List;
import java.util.Set;

public record SeatingRound(
        int roundNumber,
        List<SeatingTable> tables,
        Set<Integer> sittingOut) {
}
