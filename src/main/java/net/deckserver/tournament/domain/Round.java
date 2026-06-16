package net.deckserver.tournament.domain;

/**
 * Represents a single round of the tournament.
 * Problem fact - does not change during solving.
 */
public class Round {

    private int roundNumber; // 1-based

    public Round() {}

    public Round(int roundNumber) {
        this.roundNumber = roundNumber;
    }

    public int getRoundNumber() { return roundNumber; }
    public void setRoundNumber(int roundNumber) { this.roundNumber = roundNumber; }

    @Override
    public String toString() {
        return "Round{" + roundNumber + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Round r)) return false;
        return roundNumber == r.roundNumber;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(roundNumber);
    }
}
