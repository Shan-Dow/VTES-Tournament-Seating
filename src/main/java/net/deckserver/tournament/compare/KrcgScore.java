package net.deckserver.tournament.compare;

import java.util.Map;

public record KrcgScore(
        double total,
        Map<String, Double> rules,
        Map<String, Object> details) {
}
