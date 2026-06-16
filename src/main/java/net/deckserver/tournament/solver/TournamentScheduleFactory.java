package net.deckserver.tournament.solver;

import net.deckserver.tournament.domain.Player;
import net.deckserver.tournament.domain.Round;
import net.deckserver.tournament.domain.TableSeat;
import net.deckserver.tournament.domain.TournamentSchedule;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds an uninitialised {@link TournamentSchedule} ready for the solver.
 *
 * Key model change: a "bye" means a player sits out an ENTIRE round — they are not
 * assigned to any table at all. Every table always has exactly 4 or 5 real seats,
 * all of which must be filled by real players. There are no ghost/empty seats within
 * a table.
 *
 * The number of seats per round = playerCount - sittingOut, which always decomposes
 * cleanly into tables of 4 and 5. The solver assigns (playerCount - sittingOut) players
 * to seats each round, leaving sittingOut players unassigned (their "bye round").
 *
 * The @PlanningVariable on TableSeat remains nullable=true so the solver can represent
 * the search space, but the noEmptyNonByeSeat hard constraint forces every seat to be
 * filled — all seats are non-bye seats in this model. The byeCountEquity medium
 * constraint ensures sit-outs are distributed fairly across players and rounds.
 */
public class TournamentScheduleFactory {

    /**
     * Creates a schedule using a specific layout. Used when iterating across all valid
     * layouts to find the best-scoring solution.
     */
    public static TournamentSchedule create(List<Player> players, int roundCount,
                                             SeatLayoutCalculator.TableLayout layout) {
        int playerCount = players.size();
        List<Integer> tableSizes = SeatLayoutCalculator.tableSizes(layout);

        SeatLayoutCalculator.EqualisationResult eq =
                SeatLayoutCalculator.extraRoundsForEqualPlay(playerCount, roundCount, layout);
        int totalRounds = roundCount + eq.extraRounds();

        List<Round> rounds = new ArrayList<>();
        for (int r = 1; r <= totalRounds; r++) {
            rounds.add(new Round(r));
        }

        List<TableSeat> seats = new ArrayList<>();
        for (Round round : rounds) {
            for (int t = 0; t < tableSizes.size(); t++) {
                int tableNumber = t + 1;
                int tableSize = tableSizes.get(t);
                for (int s = 1; s <= tableSize; s++) {
                    seats.add(new TableSeat(round, tableNumber, tableSize, s, false));
                }
            }
        }

        long seatsPerRound = seats.stream()
                .filter(s -> s.getRound().getRoundNumber() == 1)
                .count();
        int expectedSeats = playerCount - layout.sittingOut();
        if (seatsPerRound != expectedSeats) {
            throw new IllegalStateException(
                    String.format("Seat count mismatch: expected %d per round, got %d",
                            expectedSeats, seatsPerRound));
        }

        return new TournamentSchedule(rounds, players, seats, totalRounds, eq.extraRounds());
    }

    /**
     * Creates a schedule with exactly the requested number of rounds. This is used for
     * apples-to-apples comparison against fixed external seating charts.
     */
    public static TournamentSchedule createExact(List<Player> players, int roundCount,
                                                 SeatLayoutCalculator.TableLayout layout) {
        int playerCount = players.size();
        List<Integer> tableSizes = SeatLayoutCalculator.tableSizes(layout);

        List<Round> rounds = new ArrayList<>();
        for (int r = 1; r <= roundCount; r++) {
            rounds.add(new Round(r));
        }

        List<TableSeat> seats = new ArrayList<>();
        for (Round round : rounds) {
            for (int t = 0; t < tableSizes.size(); t++) {
                int tableNumber = t + 1;
                int tableSize = tableSizes.get(t);
                for (int s = 1; s <= tableSize; s++) {
                    seats.add(new TableSeat(round, tableNumber, tableSize, s, false));
                }
            }
        }

        long seatsPerRound = seats.stream()
                .filter(s -> s.getRound().getRoundNumber() == 1)
                .count();
        int expectedSeats = playerCount - layout.sittingOut();
        if (seatsPerRound != expectedSeats) {
            throw new IllegalStateException(
                    String.format("Seat count mismatch: expected %d per round, got %d",
                            expectedSeats, seatsPerRound));
        }

        return new TournamentSchedule(rounds, players, seats, roundCount, 0);
    }

    /**
     * Creates a schedule using the default (minimum sit-out) layout.
     */
    public static TournamentSchedule create(List<Player> players, int roundCount) {
        int playerCount = players.size();
        SeatLayoutCalculator.TableLayout layout = SeatLayoutCalculator.calculate(playerCount);

        SeatLayoutCalculator.EqualisationResult eq =
                SeatLayoutCalculator.extraRoundsForEqualPlay(playerCount, roundCount);

        System.out.printf("Tournament: %d players, %d requested rounds, layout=%s%n",
                playerCount, roundCount, layout);
        System.out.printf("Sit-out equalisation: %s%n", eq.note());
        if (eq.extraRounds() > 0) {
            System.out.printf("Total rounds: %d%n", roundCount + eq.extraRounds());
        }

        return create(players, roundCount, layout);
    }
}
