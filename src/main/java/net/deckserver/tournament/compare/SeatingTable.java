package net.deckserver.tournament.compare;

import java.util.List;

public record SeatingTable(
        int tableNumber,
        List<Integer> playerNumbers) {
}
