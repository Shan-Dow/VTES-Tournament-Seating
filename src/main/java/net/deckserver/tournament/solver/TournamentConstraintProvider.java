package net.deckserver.tournament.solver;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import ai.timefold.solver.core.api.score.stream.*;
import net.deckserver.tournament.domain.Player;
import net.deckserver.tournament.domain.Round;
import net.deckserver.tournament.domain.TableSeat;
import org.jspecify.annotations.NonNull;

/**
 * All seating constraints for the tournament.
 * Score levels:
 *   HARD   – never violate
 *   MEDIUM – strongly avoid
 *   SOFT   – optimise in priority order (weight reflects rule priority)
 */
public class TournamentConstraintProvider implements ConstraintProvider {

    @Override
    public Constraint[] defineConstraints(@NonNull ConstraintFactory factory) {
        return new Constraint[]{
                // Hard
                noEmptyNonByeSeat(factory),
                playerSeatedOncePerRound(factory),
                noDuplicatePredatorPreyAcrossRounds(factory),   // R1
                // Medium
                noPairSharesTableInAllRounds(factory),           // R2
                vpAccessEquity(factory),                         // R3
                vpEquityForNeverSeated(factory),                 // R3: players with 0 active rounds
                byeCountEquity(factory),
                // Soft — upstream-style dominance weights for R4→R9.
                minimisePairTableRepeat(factory),                // R4 ×1,000,000
                fifthSeatAtMostOnce(factory),                    // R5 ×100,000
                noRepeatedRelativePosition(factory),             // R6 ×10,000
                noRepeatedSeatPosition(factory),                 // R7 ×1,000
                balanceStartingTransfers(factory),               // R8 ×100
                noRepeatedPositionGroup(factory),                // R9 ×1
        };
    }

    // =========================================================================
    // HARD CONSTRAINTS
    // =========================================================================


    /**
     * HARD: Every non-bye seat must have a player assigned.
     * nullable=true on @PlanningVariable allows the solver to leave any seat empty,
     * but only designated bye seats (isByeSeat()==true) should ever be null.
     * This constraint forces all real seats to be filled.
     */
    public Constraint noEmptyNonByeSeat(ConstraintFactory factory) {
        // All seats are real seats (isByeSeat is always false in the current model).
        // Players sit out entire rounds rather than occupying ghost seats within a table,
        // so every seat that exists must be filled by a real player.
        // Must use forEachIncludingNullVars: forEach() skips entities where the planning
        // variable is null, which is exactly the unfilled seats we want to catch.
        return factory.forEachIncludingUnassigned(TableSeat.class)
                .filter(seat -> seat.getPlayer() == null)
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("Seat is empty");
    }

    /**
     * HARD: Each player may appear at most once per round.
     * Joins two seats in the same round with the same non-null player.
     * lessThan(getId) prevents counting each pair twice.
     */
    public Constraint playerSeatedOncePerRound(ConstraintFactory factory) {
        return factory.forEach(TableSeat.class)
                .filter(seat -> seat.getPlayer() != null)
                .join(TableSeat.class,
                        Joiners.equal(TableSeat::getPlayer),
                        Joiners.equal(s -> s.getRound().getRoundNumber()),
                        Joiners.lessThan(TableSeat::getId))
                .filter((a, b) -> b.getPlayer() != null)
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("Player seated more than once in a round");
    }

    /**
     * HARD (most important): A predator-prey relationship must not be duplicated across rounds.
     * An ordered (predatorPlayer, preyPlayer) tuple is formed by joining each seat with the
     * next seat at the same table in the same round (using getPreyPosition() for the wrap).
     * We then groupBy (predator, prey) and count occurrences across all rounds.
     * Any count > 1 means the relationship repeats — penalise (count - 1) times.
     * Using groupBy here is both simpler and more efficient than the original 4-way join,
     * and avoids the double-counting problem the previous approach had.
     */
    public Constraint noDuplicatePredatorPreyAcrossRounds(ConstraintFactory factory) {
        return factory.forEach(TableSeat.class)
                .filter(pred -> pred.getPlayer() != null)
                .join(TableSeat.class,
                        Joiners.equal(s -> s.getRound().getRoundNumber(),
                                      s -> s.getRound().getRoundNumber()),
                        Joiners.equal(TableSeat::getTableNumber),
                        Joiners.equal(TableSeat::getPreyPosition, TableSeat::getSeatPosition),
                        Joiners.filtering((pred, prey) -> prey.getPlayer() != null))
                .groupBy(
                        (pred, prey) -> pred.getPlayer(),
                        (pred, prey) -> prey.getPlayer(),
                        ConstraintCollectors.countBi())
                .filter((predPlayer, preyPlayer, count) -> count > 1)
                .penalize(HardMediumSoftScore.ONE_HARD,
                        (predPlayer, preyPlayer, count) -> count - 1)
                .asConstraint("Duplicate predator-prey relationship across rounds");
    }

    // =========================================================================
    // MEDIUM CONSTRAINTS
    // =========================================================================

    /**
     * MEDIUM: No pair of players should share a table in every single round.
     * Counts co-table appearances per canonical (playerA, playerB) pair.
     */
    Constraint noPairSharesTableInAllRounds(ConstraintFactory factory) {
        var roundCount = factory.forEach(Round.class)
                .groupBy(ConstraintCollectors.count());

        var pairCounts = factory.forEach(TableSeat.class)
                .filter(s -> s.getPlayer() != null)
                .join(TableSeat.class,
                        Joiners.equal(s -> s.getRound().getRoundNumber()),
                        Joiners.equal(TableSeat::getTableNumber),
                        Joiners.lessThan(s -> s.getPlayer().getId(),
                                s -> s.getPlayer().getId()),
                        Joiners.filtering((a, b) -> b.getPlayer() != null))
                .groupBy(
                        (a, b) -> a.getPlayer(),
                        (a, b) -> b.getPlayer(),
                        ConstraintCollectors.countBi())
                .map(PlayerPairCount::new);

        return pairCounts
                .join(roundCount)
                .filter((pair, rounds) -> pair.count() >= rounds)
                .penalize(HardMediumSoftScore.ONE_MEDIUM, (pair, rounds) -> Math.toIntExact(pair.count()))
                .asConstraint("Pair shares a table in all rounds");
    }

    // =========================================================================
    // SOFT CONSTRAINTS
    // =========================================================================

    /**
     * SOFT (weight 100): Minimise pairs of players sharing a table more than once.
     */
    Constraint minimisePairTableRepeat(ConstraintFactory factory) {
        return factory.forEach(TableSeat.class)
                .filter(s -> s.getPlayer() != null)
                .join(TableSeat.class,
                        Joiners.equal(s -> s.getRound().getRoundNumber()),
                        Joiners.equal(TableSeat::getTableNumber),
                        Joiners.lessThan(s -> s.getPlayer().getId(),
                                s -> s.getPlayer().getId()),
                        Joiners.filtering((a, b) -> b.getPlayer() != null))
                .groupBy(
                        (a, b) -> a.getPlayer(),
                        (a, b) -> b.getPlayer(),
                        ConstraintCollectors.countBi())
                .filter((p1, p2, count) -> count > 1)
                .penalize(HardMediumSoftScore.ONE_SOFT, (p1, p2, count) -> 1_000_000 * (count - 1))
                .asConstraint("Pair shares a table more than once");
    }

    /**
     * SOFT (weight 1, R9): No pair of players should repeat the same relative
     * position group more than once. The official groups are adjacent and non-neighbour.
     */
    Constraint noRepeatedPositionGroup(ConstraintFactory factory) {
        return factory.forEach(TableSeat.class)
                .filter(s -> s.getPlayer() != null)
                .join(TableSeat.class,
                        Joiners.equal(s -> s.getRound().getRoundNumber()),
                        Joiners.equal(TableSeat::getTableNumber),
                        // Canonical ordering: lower player id is always the "left" seat
                        Joiners.lessThan(s -> s.getPlayer().getId(),
                                s -> s.getPlayer().getId()),
                        Joiners.filtering((a, b) -> b.getPlayer() != null))
                .groupBy(
                        (a, b) -> a.getPlayer(),
                        (a, b) -> b.getPlayer(),
                        TournamentConstraintProvider::relativePositionGroup,
                        ConstraintCollectors.countBi())
                .filter((p1, p2, group, count) -> count > 1)
                .penalize(HardMediumSoftScore.ONE_SOFT, (p1, p2, group, count) -> count - 1)
                .asConstraint("Pair repeats the same relative position group");
    }

    /**
     * Returns true if two seats at the same table are directly adjacent (neighbours),
     * including the circular wrap between the last seat and seat 1.
     * Both directions are covered: |posA - posB| == 1 handles linear adjacency,
     * and the wrap check handles seat 1 and seat N being neighbours on the circle.
     */
    private static boolean isDirectlyAdjacent(TableSeat a, TableSeat b) {
        int posA = a.getSeatPosition();
        int posB = b.getSeatPosition();
        int size = a.getTableSize();
        int gap = Math.abs(posA - posB);
        return gap == 1 || gap == size - 1;
    }

    /**
     * SOFT (weight 80, R5): A player should not sit in the 5th seat more than once.
     * Seat 5 only exists at 5-player tables.
     */
    public Constraint fifthSeatAtMostOnce(ConstraintFactory factory) {
        return factory.forEach(TableSeat.class)
                .filter(s -> s.getPlayer() != null && s.isFifthSeat())
                .groupBy(TableSeat::getPlayer, ConstraintCollectors.count())
                .filter((player, count) -> count > 1)
                .penalize(HardMediumSoftScore.ONE_SOFT, (player, count) -> 100_000 * (count - 1))
                .asConstraint("Player sits in 5th seat more than once");
    }

    /**
     * SOFT (weight 40, R7): A player should not sit in the same absolute seat position more than once.
     */
    public Constraint noRepeatedSeatPosition(ConstraintFactory factory) {
        return factory.forEach(TableSeat.class)
                .filter(s -> s.getPlayer() != null)
                .groupBy(TableSeat::getPlayer, TableSeat::getSeatPosition,
                        ConstraintCollectors.count())
                .filter((player, pos, count) -> count > 1)
                .penalize(HardMediumSoftScore.ONE_SOFT, (player, pos, count) -> 1_000 * (count - 1))
                .asConstraint("Player sits in same seat position more than once");
    }

    /**
     * SOFT (weight 10,000, R6): No pair of players should repeat the same official
     * relative position: prey, predator, grand-prey at a 5, grand-predator at a 5,
     * or cross-table at a 4.
     */
    Constraint noRepeatedRelativePosition(ConstraintFactory factory) {
        return factory.forEach(TableSeat.class)
                .filter(s -> s.getPlayer() != null)
                .join(TableSeat.class,
                        Joiners.equal(s -> s.getRound().getRoundNumber()),
                        Joiners.equal(TableSeat::getTableNumber),
                        Joiners.lessThan(s -> s.getPlayer().getId(),
                                s -> s.getPlayer().getId()),
                        Joiners.filtering((a, b) -> b.getPlayer() != null))
                .groupBy(
                        (a, b) -> a.getPlayer(),
                        (a, b) -> b.getPlayer(),
                        TournamentConstraintProvider::relativePosition,
                        ConstraintCollectors.countBi())
                .filter((p1, p2, position, count) -> count > 1)
                .penalize(HardMediumSoftScore.ONE_SOFT, (p1, p2, position, count) -> 10_000 * (count - 1))
                .asConstraint("Pair repeats the same relative position");
    }

    /**
     * MEDIUM (R3): Available VP access should be equitably distributed across all players.
     * A player's total available VPs equals the sum of the table sizes they play at
     * (4 or 5 per active round). Players at larger tables have more VP opportunities;
     * this constraint ensures that advantage is shared fairly across the tournament.
     * Mirrors the official VEKN R3 criterion (third-highest priority).
     */
    public Constraint vpAccessEquity(ConstraintFactory factory) {
        var vpAccess = factory.forEach(TableSeat.class)
                .filter(s -> s.getPlayer() != null)
                .groupBy(TableSeat::getPlayer,
                        ConstraintCollectors.sum(TableSeat::getTableSize),
                        ConstraintCollectors.count())
                .map(PlayerVpAccess::new);

        return vpAccess
                .join(vpAccess, Joiners.lessThan(pva -> pva.player().getId()))
                .filter((a, b) -> vpRatioDifference(a, b) > 0)
                .penalize(HardMediumSoftScore.ONE_MEDIUM,
                        TournamentConstraintProvider::vpRatioDifference)
                .asConstraint("Unequal VP access across players");
    }

    /**
     * MEDIUM (R3 supplement): Players who are never seated have 0 VP access, creating a large
     * disparity vs. active players. Since vpAccessEquity only compares players appearing in at
     * least one seat, this constraint fills the gap by penalising each (never-seated, active)
     * pair by the active player's VP total — equivalent to the missing |0 − totalVps| term.
     */
    Constraint vpEquityForNeverSeated(ConstraintFactory factory) {
        var vpAccess = factory.forEach(TableSeat.class)
                .filter(s -> s.getPlayer() != null)
                .groupBy(TableSeat::getPlayer,
                        ConstraintCollectors.sum(TableSeat::getTableSize),
                        ConstraintCollectors.count())
                .map(PlayerVpAccess::new);

        return factory.forEach(Player.class)
                .ifNotExists(TableSeat.class,
                        Joiners.equal(p -> p, TableSeat::getPlayer))
                .join(vpAccess)
                .penalize(HardMediumSoftScore.ONE_MEDIUM,
                        (neverSeated, pva) -> pva.totalVps())
                .asConstraint("Never-seated player VP equity");
    }

    /**
     * MEDIUM: Players should play the same number of rounds, which is equivalent to
     * equal sit-out counts for a fixed number of total rounds.
     */
    Constraint byeCountEquity(ConstraintFactory factory) {
        var playedCounts = factory.forEach(TableSeat.class)
                .filter(s -> s.getPlayer() != null)
                .groupBy(TableSeat::getPlayer, ConstraintCollectors.count())
                .map(PlayerPlayedCount::new);

        var activePlayerSpread = playedCounts
                .join(playedCounts, Joiners.lessThan(ppc -> ppc.player().getId()))
                .filter((a, b) -> Math.abs(a.playedRounds() - b.playedRounds()) > 0)
                .penalize(HardMediumSoftScore.ONE_MEDIUM,
                        (a, b) -> Math.abs(a.playedRounds() - b.playedRounds()))
                .asConstraint("Unequal play counts between players");

        return activePlayerSpread;
    }

    /**
     * SOFT (weight 100, R8): Average starting transfers should be balanced across all players.
     * Transfer values: seat 1→1, 2→2, 3→3, 4→4, 5→4.
     */
    public Constraint balanceStartingTransfers(ConstraintFactory factory) {
        var totals = factory.forEach(TableSeat.class)
                .filter(s -> s.getPlayer() != null)
                .groupBy(TableSeat::getPlayer,
                        ConstraintCollectors.sum(TableSeat::getStartingTransferValue),
                        ConstraintCollectors.count())
                .map(PlayerTransferTotal::new);  // BiConstraintStream<Player,Int> -> Uni<PlayerTransferTotal>

        return totals
                .join(totals,
                        // Canonical ordering: only count each pair once.
                        Joiners.lessThan(ptt -> ptt.player().getId()))
                .filter((a, b) -> transferRatioDifference(a, b) > 0)
                .penalize(HardMediumSoftScore.ONE_SOFT,
                        (a, b) -> 100 * transferRatioDifference(a, b))
                .asConstraint("Unbalanced starting transfers between players");
    }

    private static int relativePosition(TableSeat a, TableSeat b) {
        int size = a.getTableSize();
        int clockwiseDistance = Math.floorMod(b.getSeatPosition() - a.getSeatPosition(), size);
        if (clockwiseDistance == 1) return 1;              // prey
        if (clockwiseDistance == size - 1) return 2;       // predator
        if (size == 5 && clockwiseDistance == 2) return 3; // grand-prey
        if (size == 5 && clockwiseDistance == 3) return 4; // grand-predator
        if (size == 4 && clockwiseDistance == 2) return 5; // cross-table
        throw new IllegalStateException("Unknown relative position for " + a + " and " + b);
    }

    private static int relativePositionGroup(TableSeat a, TableSeat b) {
        return isDirectlyAdjacent(a, b) ? 1 : 2;
    }

    private static int vpRatioDifference(PlayerVpAccess a, PlayerVpAccess b) {
        return Math.toIntExact(Math.abs(a.totalVps() * b.playedRounds() - b.totalVps() * a.playedRounds()));
    }

    private static int transferRatioDifference(PlayerTransferTotal a, PlayerTransferTotal b) {
        return Math.toIntExact(Math.abs(a.total() * b.playedRounds() - b.total() * a.playedRounds()));
    }

    private record PlayerPairCount(Player playerA, Player playerB, long count) {}

    /** Holder used by vpAccessEquity and vpEquityForNeverSeated. */
    private record PlayerVpAccess(Player player, long totalVps, long playedRounds) {}

    /** Holder used by balanceStartingTransfers to collapse a Bi-stream into a Uni-stream. */
    private record PlayerTransferTotal(Player player, long total, long playedRounds) {}

    private record PlayerPlayedCount(Player player, long playedRounds) {}
}
