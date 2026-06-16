package net.deckserver.tournament.domain;

import ai.timefold.solver.core.api.domain.common.PlanningId;
import ai.timefold.solver.core.api.domain.entity.PlanningEntity;
import ai.timefold.solver.core.api.domain.variable.PlanningVariable;
import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

/**
 * Represents a single seat at a table in a given round.
 *
 * This is the PLANNING ENTITY. The planning variable is the Player assigned to this seat.
 *
 * A seat is identified by:
 *   - round
 *   - tableNumber (1-based, within the round)
 *   - seatPosition (1-based, 1..4 or 1..5)
 *
 * Predator-prey relationship: the player at seat N is the predator of the player at seat N+1.
 * The player at the last seat is predator of seat 1 (circular).
 *
 * Starting transfer values by seat:
 *   Seat 1 → 1, Seat 2 → 2, Seat 3 → 3, Seat 4 → 4, Seat 5 → 4
 */
@PlanningEntity
@JsonIdentityInfo(scope = TableSeat.class, generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
public class TableSeat {

    @PlanningId
    private String id; // e.g. "R1-T2-S3"

    private Round round;
    private int tableNumber;   // 1-based within the round
    private int tableSize;     // 4 or 5
    private int seatPosition;  // 1-based

    private boolean byeSeat;    // true = this slot may be left empty (bye)

    @PlanningVariable
    private Player player;

    public TableSeat() {}

    public TableSeat(Round round, int tableNumber, int tableSize, int seatPosition) {
        this(round, tableNumber, tableSize, seatPosition, false);
    }

    public TableSeat(Round round, int tableNumber, int tableSize, int seatPosition, boolean byeSeat) {
        this.round = round;
        this.tableNumber = tableNumber;
        this.tableSize = tableSize;
        this.seatPosition = seatPosition;
        this.byeSeat = byeSeat;
        this.id = "R" + round.getRoundNumber() + "-T" + tableNumber + "-S" + seatPosition + (byeSeat ? "-BYE" : "");
    }

    // -------------------------------------------------------------------------
    // Derived helpers used by constraints
    // -------------------------------------------------------------------------

    /**
     * Returns the seat position of this seat's prey (the next seat at the table, wrapping around).
     */
    public int getPreyPosition() {
        return (seatPosition % tableSize) + 1;
    }

    /**
     * Starting transfer value for this seat.
     * Seat 1=1, 2=2, 3=3, 4=4, 5=4
     */
    public int getStartingTransferValue() {
        return Math.min(seatPosition, 4);
    }

    /**
     * Whether this is the 5th seat (only exists on 5-player tables).
     */
    public boolean isFifthSeat() {
        return seatPosition == 5;
    }

    // -------------------------------------------------------------------------
    // Getters / setters
    // -------------------------------------------------------------------------

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Round getRound() { return round; }
    public void setRound(Round round) { this.round = round; }

    public int getTableNumber() { return tableNumber; }
    public void setTableNumber(int tableNumber) { this.tableNumber = tableNumber; }

    public int getTableSize() { return tableSize; }
    public void setTableSize(int tableSize) { this.tableSize = tableSize; }

    public int getSeatPosition() { return seatPosition; }
    public void setSeatPosition(int seatPosition) { this.seatPosition = seatPosition; }

    public boolean isByeSeat() { return byeSeat; }
    public void setByeSeat(boolean byeSeat) { this.byeSeat = byeSeat; }

    public Player getPlayer() { return player; }
    public void setPlayer(Player player) { this.player = player; }

    @Override
    public String toString() {
        return id + "=" + (player != null ? player.getName() : "BYE");
    }
}
