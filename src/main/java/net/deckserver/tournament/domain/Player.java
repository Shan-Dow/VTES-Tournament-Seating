package net.deckserver.tournament.domain;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

/**
 * Represents a tournament participant.
 * Players are the problem facts - they do not change during solving.
 */
@JsonIdentityInfo(scope = Player.class, generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
public class Player {

    private String id;
    private String name;

    // Required by OptaPlanner/Jackson
    public Player() {}

    public Player(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    @Override
    public String toString() {
        return "Player{" + id + ":" + name + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Player p)) return false;
        return id.equals(p.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
