package net.deckserver.tournament.compare;

import net.deckserver.tournament.domain.Player;
import net.deckserver.tournament.domain.Round;
import net.deckserver.tournament.domain.TableSeat;
import net.deckserver.tournament.domain.TournamentSchedule;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class TimefoldPlanMapper {

    public TournamentSchedule toSchedule(SeatingPlan plan) {
        List<Player> players = IntStream.rangeClosed(1, plan.playerCount())
                .mapToObj(i -> new Player(playerId(i), "Player " + i))
                .collect(Collectors.toList());
        Map<Integer, Player> playerByNumber = new HashMap<>();
        for (int i = 0; i < players.size(); i++) {
            playerByNumber.put(i + 1, players.get(i));
        }

        List<Round> rounds = plan.rounds().stream()
                .map(round -> new Round(round.roundNumber()))
                .toList();
        Map<Integer, Round> roundByNumber = rounds.stream()
                .collect(Collectors.toMap(Round::getRoundNumber, round -> round));

        List<TableSeat> seats = new ArrayList<>();
        for (SeatingRound seatingRound : plan.rounds()) {
            Round round = roundByNumber.get(seatingRound.roundNumber());
            for (SeatingTable table : seatingRound.tables()) {
                int tableSize = table.playerNumbers().size();
                for (int i = 0; i < table.playerNumbers().size(); i++) {
                    TableSeat seat = new TableSeat(round, table.tableNumber(), tableSize, i + 1, false);
                    seat.setPlayer(playerByNumber.get(table.playerNumbers().get(i)));
                    seats.add(seat);
                }
            }
        }

        return new TournamentSchedule(rounds, players, seats, plan.requestedRounds(), 0);
    }

    public SeatingPlan fromSchedule(TournamentSchedule schedule, String source) {
        int playerCount = schedule.getPlayers().size();
        List<SeatingRound> rounds = schedule.getSeats().stream()
                .collect(Collectors.groupingBy(
                        seat -> seat.getRound().getRoundNumber(),
                        java.util.TreeMap::new,
                        Collectors.toList()))
                .entrySet().stream()
                .map(entry -> toSeatingRound(entry.getKey(), entry.getValue(), playerCount))
                .toList();
        return new SeatingPlan(playerCount, schedule.getRequestedRounds(), rounds, source);
    }

    private SeatingRound toSeatingRound(int roundNumber, List<TableSeat> seats, int playerCount) {
        List<SeatingTable> tables = seats.stream()
                .collect(Collectors.groupingBy(
                        TableSeat::getTableNumber,
                        java.util.TreeMap::new,
                        Collectors.toList()))
                .entrySet().stream()
                .map(entry -> {
                    List<Integer> playerNumbers = entry.getValue().stream()
                            .sorted(Comparator.comparingInt(TableSeat::getSeatPosition))
                            .map(TableSeat::getPlayer)
                            .map(Player::getId)
                            .map(TimefoldPlanMapper::playerNumber)
                            .toList();
                    return new SeatingTable(entry.getKey(), playerNumbers);
                })
                .toList();

        Set<Integer> seated = tables.stream()
                .flatMap(table -> table.playerNumbers().stream())
                .collect(Collectors.toSet());
        Set<Integer> sittingOut = new TreeSet<>();
        for (int player = 1; player <= playerCount; player++) {
            if (!seated.contains(player)) {
                sittingOut.add(player);
            }
        }
        return new SeatingRound(roundNumber, tables, sittingOut);
    }

    public void validate(SeatingPlan plan) {
        for (SeatingRound round : plan.rounds()) {
            Set<Integer> seen = new HashSet<>();
            for (SeatingTable table : round.tables()) {
                if (table.playerNumbers().size() < 4 || table.playerNumbers().size() > 5) {
                    throw new IllegalArgumentException("Round " + round.roundNumber()
                            + " table " + table.tableNumber() + " has invalid size "
                            + table.playerNumbers().size());
                }
                for (int player : table.playerNumbers()) {
                    if (player < 1 || player > plan.playerCount()) {
                        throw new IllegalArgumentException("Player number out of range: " + player);
                    }
                    if (!seen.add(player)) {
                        throw new IllegalArgumentException("Player " + player
                                + " appears more than once in round " + round.roundNumber());
                    }
                }
            }
        }
    }

    private static String playerId(int playerNumber) {
        return "P" + playerNumber;
    }

    private static int playerNumber(String playerId) {
        if (playerId == null || !playerId.startsWith("P")) {
            throw new IllegalArgumentException("Expected player id Pn, got: " + playerId);
        }
        return Integer.parseInt(playerId.substring(1));
    }
}
