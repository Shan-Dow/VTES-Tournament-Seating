package net.deckserver.tournament.domain;

import ai.timefold.solver.core.api.domain.solution.PlanningEntityCollectionProperty;
import ai.timefold.solver.core.api.domain.solution.PlanningScore;
import ai.timefold.solver.core.api.domain.solution.PlanningSolution;
import ai.timefold.solver.core.api.domain.solution.ProblemFactCollectionProperty;
import ai.timefold.solver.core.api.domain.valuerange.ValueRangeProvider;
import ai.timefold.solver.core.api.score.HardMediumSoftScore;

import java.util.List;

/**
 * The planning solution for the tournament seating problem.
 *
 * Score levels:
 *   HARD   - must not be violated (predator-prey uniqueness, player seated once per round)
 *   MEDIUM - strongly preferred (no pair shares a table across all rounds)
 *   SOFT   - optimise where possible (position variety, table size equity, transfer balance, etc.)
 */
@PlanningSolution
public class TournamentSchedule {

    // -------------------------------------------------------------------------
    // Problem facts (do not change during solving)
    // -------------------------------------------------------------------------

    @ProblemFactCollectionProperty
    private List<Round> rounds;

    @ProblemFactCollectionProperty
    @ValueRangeProvider
    private List<Player> players;

    // -------------------------------------------------------------------------
    // Planning entities
    // -------------------------------------------------------------------------

    @PlanningEntityCollectionProperty
    private List<TableSeat> seats;

    // -------------------------------------------------------------------------
    // Score
    // -------------------------------------------------------------------------

    @PlanningScore
    private HardMediumSoftScore score;

    // -------------------------------------------------------------------------
    // Metadata (not used by solver, useful for REST/UI)
    // -------------------------------------------------------------------------

    private int roundCount;       // total rounds including any extras
    private int requestedRounds;  // rounds originally requested by the user
    private int extraRounds;      // rounds added for bye equalisation
    private int playerCount;

    // Required by OptaPlanner
    public TournamentSchedule() {}

    public TournamentSchedule(List<Round> rounds, List<Player> players, List<TableSeat> seats) {
        this(rounds, players, seats, rounds.size(), 0);
    }

    public TournamentSchedule(List<Round> rounds, List<Player> players, List<TableSeat> seats,
                               int totalRounds, int extraRounds) {
        this.rounds = rounds;
        this.players = players;
        this.seats = seats;
        this.roundCount = totalRounds;
        this.requestedRounds = totalRounds - extraRounds;
        this.extraRounds = extraRounds;
        this.playerCount = players.size();
    }

    // -------------------------------------------------------------------------
    // Getters / setters
    // -------------------------------------------------------------------------

    public List<Round> getRounds() { return rounds; }
    public void setRounds(List<Round> rounds) { this.rounds = rounds; }

    public List<Player> getPlayers() { return players; }
    public void setPlayers(List<Player> players) { this.players = players; }

    public List<TableSeat> getSeats() { return seats; }
    public void setSeats(List<TableSeat> seats) { this.seats = seats; }

    public HardMediumSoftScore getScore() { return score; }
    public void setScore(HardMediumSoftScore score) { this.score = score; }

    public int getRoundCount() { return roundCount; }
    public void setRoundCount(int roundCount) { this.roundCount = roundCount; }

    public int getRequestedRounds() { return requestedRounds; }
    public void setRequestedRounds(int requestedRounds) { this.requestedRounds = requestedRounds; }

    public int getExtraRounds() { return extraRounds; }
    public void setExtraRounds(int extraRounds) { this.extraRounds = extraRounds; }

    public int getPlayerCount() { return playerCount; }
    public void setPlayerCount(int playerCount) { this.playerCount = playerCount; }
}
