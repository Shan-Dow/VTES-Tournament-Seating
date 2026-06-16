package net.deckserver.tournament.compare;

import java.util.LinkedHashMap;
import java.util.Map;

public class BatchComparisonCase {
    public int playerCount;
    public int preliminaryRounds;
    public int attempts;
    public long nextTimeSeconds;
    public boolean confirmedSuperior;
    public String lastRunAt;
    public String confirmedAt;
    public String archonTimefoldScore;
    public String timefoldTimefoldScore;
    public double archonKrcgTotal;
    public double timefoldKrcgTotal;
    public double krcgDelta;
    public Map<String, Double> archonKrcgRules = new LinkedHashMap<>();
    public Map<String, Double> timefoldKrcgRules = new LinkedHashMap<>();
    public SeatingPlan archonPlan;
    public SeatingPlan timefoldPlan;
}
