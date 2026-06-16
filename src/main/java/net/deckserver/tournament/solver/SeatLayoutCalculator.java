package net.deckserver.tournament.solver;

/**
 * Calculates the optimal table layout for a given number of players.
 * A "bye" means a player sits out an entire round — they are not assigned to any
 * table at all. The remaining (N - sittingOut) players fill tables of exactly 4 or 5
 * real players, with no ghost/empty seats.
 * Rules:
 *  - Every table must have exactly 4 or 5 real players (never fewer).
 *  - Prefer 5-seat tables.
 *  - Minimise the number of players sitting out per round.
 *  - Add the minimum number of extra rounds required to equalise the number of
 *    games each player plays across all rounds.
 */
public class SeatLayoutCalculator {

    /**
     * Describes how many players sit at tables vs. sit out in a single round.
     *
     * @param fiveSeatTables  number of 5-player tables
     * @param fourSeatTables  number of 4-player tables
     * @param sittingOut      number of players who sit out this round (0 when N fits cleanly)
     */
    public record TableLayout(int fiveSeatTables, int fourSeatTables, int sittingOut) {

        /** Total seated players (real players at tables, not including those sitting out). */
        public int seatedPlayers() {
            return fiveSeatTables * 5 + fourSeatTables * 4;
        }

        /** Total number of tables. */
        public int tableCount() {
            return fiveSeatTables + fourSeatTables;
        }

        @Override
        public String toString() {
            return String.format("TableLayout{5-seat=%d, 4-seat=%d, sitting-out=%d, tables=%d}",
                    fiveSeatTables, fourSeatTables, sittingOut, tableCount());
        }

        /** Total sitting out */
        public int byeSeats() {
            return sittingOut;
        }
    }

    /**
     * Result of the bye-equalisation calculation.
     * @param extraRounds  number of extra rounds to add
     * @param note         human-readable explanation
     */
    public record EqualisationResult(int extraRounds, String note) {}

    /**
     * Returns the optimal layout for the given number of players.
     * Tries increasing numbers of players sitting out (0, 1, 2, ...) until
     * the remaining players can be divided into tables of exactly 4 or 5.
     * Maximises 5-seat tables within the valid solution.
     */
    public static TableLayout calculate(int playerCount) {
        if (playerCount < 4) {
            throw new IllegalArgumentException("Cannot seat fewer than 4 players.");
        }

        for (int sittingOut = 0; sittingOut < playerCount; sittingOut++) {
            int playing = playerCount - sittingOut;
            if (playing < 4) break;

            // Find max 5-seat tables for `playing` players
            for (int five = playing / 5; five >= 0; five--) {
                int remainder = playing - 5 * five;
                if (remainder >= 0 && remainder % 4 == 0) {
                    int four = remainder / 4;
                    if (five + four > 0) {
                        return new TableLayout(five, four, sittingOut);
                    }
                }
            }
        }
        throw new IllegalStateException("Cannot find layout for " + playerCount + " players.");
    }

    /**
     * Returns ALL valid layouts for the given player count, ordered by:
     *   1. Fewest sit-outs first (most players playing)
     *   2. Most 5-seat tables first (within same sit-out count)
     * The solver will be run against each layout independently; the best scoring
     * solution across all layouts is selected as the final answer.
     *
     * @param playerCount   total number of players
     * @param maxSitOut     maximum sit-outs to consider (typically 3-4)
     */
    public static java.util.List<TableLayout> allLayouts(int playerCount, int maxSitOut) {
        if (playerCount < 4) {
            throw new IllegalArgumentException("Cannot seat fewer than 4 players.");
        }
        java.util.List<TableLayout> layouts = new java.util.ArrayList<>();
        for (int sittingOut = 0; sittingOut <= Math.min(maxSitOut, playerCount - 4); sittingOut++) {
            int playing = playerCount - sittingOut;
            if (playing < 4) break;
            for (int five = playing / 5; five >= 0; five--) {
                int remainder = playing - 5 * five;
                if (remainder >= 0 && remainder % 4 == 0) {
                    int four = remainder / 4;
                    if (five + four > 0) {
                        layouts.add(new TableLayout(five, four, sittingOut));
                        break; // one layout per sit-out count (max 5-seat tables already)
                    }
                }
            }
        }
        return layouts;
    }

    /**
     * Returns ALL valid layouts for the given player count with a default max of 4 sit-outs.
     */
    public static java.util.List<TableLayout> allLayouts(int playerCount) {
        return allLayouts(playerCount, 4);
    }

    /**
     * Returns a list of table sizes (5 or 4) for a given layout, 5-seat tables first.
     */
    public static java.util.List<Integer> tableSizes(TableLayout layout) {
        java.util.List<Integer> sizes = new java.util.ArrayList<>();
        for (int i = 0; i < layout.fiveSeatTables(); i++) sizes.add(5);
        for (int i = 0; i < layout.fourSeatTables(); i++) sizes.add(4);
        return sizes;
    }

    /**
     * Calculates whether extra rounds should be added so every player plays
     * the same number of games across all rounds.
     * With S players sitting out per round over R rounds, total sit-outs = R * S.
     * Each player sits out equally when N divides R * S evenly.
     *
     * @param playerCount     total number of players
     * @param requestedRounds number of rounds the user asked for
     */
    public static EqualisationResult extraRoundsForEqualPlay(int playerCount, int requestedRounds) {
        TableLayout layout = calculate(playerCount);
        return extraRoundsForEqualPlay(playerCount, requestedRounds, layout);
    }

    /**
     * Layout-aware equalisation. This must use the sit-out count from the layout
     * currently being solved, not just the default minimum-sit-out layout.
     */
    public static EqualisationResult extraRoundsForEqualPlay(int playerCount, int requestedRounds,
                                                             TableLayout layout) {
        int sittingOut = layout.sittingOut();

        if (sittingOut == 0) {
            return new EqualisationResult(0, "No sit-outs needed — all players play every round.");
        }

        // Check if requested rounds already give equal sit-out distribution
        if ((requestedRounds * sittingOut) % playerCount == 0) {
            int sitoutsPerPlayer = (requestedRounds * sittingOut) / playerCount;
            return new EqualisationResult(0,
                    String.format("Each player sits out exactly %d time(s) across %d rounds.",
                            sitoutsPerPlayer, requestedRounds));
        }

        int totalWithExtra = requestedRounds + 1;
        while ((totalWithExtra * sittingOut) % playerCount != 0) {
            totalWithExtra++;
        }

        int extraRounds = totalWithExtra - requestedRounds;
        int sitoutsPerPlayer = (totalWithExtra * sittingOut) / playerCount;
        return new EqualisationResult(extraRounds,
                String.format("Adding %d extra round(s) (%d total) so each player sits out exactly %d time(s).",
                        extraRounds, totalWithExtra, sitoutsPerPlayer));
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int t = b;
            b = a % b;
            a = t;
        }
        return a;
    }
}
