package net.deckserver.tournament.compare;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class BatchComparisonState {
    public int minPlayers;
    public int maxPlayers;
    public List<Integer> rounds = new ArrayList<>();
    public long stepSeconds;
    public String createdAt = Instant.now().toString();
    public String updatedAt = Instant.now().toString();
    public List<BatchComparisonCase> cases = new ArrayList<>();
}
