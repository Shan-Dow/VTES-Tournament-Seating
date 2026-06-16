#!/usr/bin/env python3
import json
import math
import sys
from collections import defaultdict

RULES = [
    ("R1", "predator-prey", 10**10),
    ("R2", "opponent thrice", 10**9),
    ("R3", "available vps", 10**8),
    ("R4", "opponent twice", 10**6),
    ("R5", "fifth seat", 10**5),
    ("R6", "position", 10**4),
    ("R7", "same seat", 10**3),
    ("R8", "starting transfers", 10**2),
    ("R9", "position group", 1),
]


def relation(table_size, clockwise_distance):
    # Returns the KRCG relation vector:
    # opponent, prey, grand-prey, grand-predator, predator, cross-table,
    # neighbour, non-neighbour.
    if table_size == 4:
        if clockwise_distance == 1:
            return (1, 1, 0, 0, 0, 0, 1, 0)
        if clockwise_distance == 2:
            return (1, 0, 0, 0, 0, 1, 0, 1)
        if clockwise_distance == 3:
            return (1, 0, 0, 0, 1, 0, 1, 0)
    if table_size == 5:
        if clockwise_distance == 1:
            return (1, 1, 0, 0, 0, 0, 1, 0)
        if clockwise_distance == 2:
            return (1, 0, 1, 0, 0, 0, 0, 1)
        if clockwise_distance == 3:
            return (1, 0, 0, 1, 0, 0, 0, 1)
        if clockwise_distance == 4:
            return (1, 0, 0, 0, 1, 0, 1, 0)
    raise ValueError(f"Unsupported table size/distance: {table_size}/{clockwise_distance}")


def stddev(values):
    if not values:
        return 0.0
    mean = sum(values) / len(values)
    return math.sqrt(sum((value - mean) ** 2 for value in values) / len(values))


def score(plan):
    player_count = int(plan["playerCount"])
    rounds = plan["rounds"]
    rounds_count = len(rounds)

    played = defaultdict(int)
    available_vps = defaultdict(int)
    transfers = defaultdict(int)
    seat_counts = defaultdict(lambda: defaultdict(int))
    opponents = defaultdict(lambda: [0] * 8)

    for round_index, round_tables in enumerate(rounds, 1):
        seen = set()
        for table in round_tables:
            table = [int(player) for player in table]
            table_size = len(table)
            if table_size not in (4, 5):
                raise ValueError(f"Round {round_index} has invalid table size {table_size}: {table}")
            for seat_index, player in enumerate(table):
                if player in seen:
                    raise ValueError(f"Player {player} appears twice in round {round_index}")
                seen.add(player)
                played[player] += 1
                available_vps[player] += table_size
                transfers[player] += min(seat_index + 1, 4)
                seat_counts[player][seat_index + 1] += 1
                for distance in range(1, table_size):
                    opponent = table[(seat_index + distance) % table_size]
                    vector = relation(table_size, distance)
                    key = (player, opponent)
                    for i, value in enumerate(vector):
                        opponents[key][i] += value

    active_players = [player for player in range(1, player_count + 1) if played[player] > 0]
    vps = [available_vps[player] / played[player] for player in active_players]
    transfer_values = [transfers[player] / played[player] for player in active_players]

    r3 = stddev(vps)
    r8 = stddev(transfer_values)
    r7 = [
        {"player": player, "seat": seat}
        for player in range(1, player_count + 1)
        for seat, count in seat_counts[player].items()
        if count > 1
    ]
    r5 = [{"player": violation["player"]} for violation in r7 if violation["seat"] == 5]

    r4 = []
    r2 = []
    r6 = []
    r1 = []
    r9 = []
    for a in range(1, player_count + 1):
        for b in range(a + 1, player_count + 1):
            # KRCG stores ordered pair relation vectors. R4/R2 are symmetric and
            # are counted once by using the a<b pair.
            pair_vector = opponents[(a, b)]
            if pair_vector[0] > 1:
                r4.append({"player_1": a, "player_2": b})
            if pair_vector[0] >= rounds_count:
                r2.append({"player_1": a, "player_2": b})
            for relation_index, count in enumerate(pair_vector[1:6], 1):
                if count > 1:
                    item = {"player_1": a, "player_2": b, "position": relation_index}
                    r6.append(item)
                    if relation_index in (1, 4):
                        r1.append(item)
            for group_index, count in enumerate(pair_vector[6:], 1):
                if count > 1:
                    r9.append({"player_1": a, "player_2": b, "position": group_index})

    rule_values = {
        "R1": float(len(r1)),
        "R2": float(len(r2)),
        "R3": r3,
        "R4": float(len(r4)),
        "R5": float(len(r5)),
        "R6": float(len(r6)),
        "R7": float(len(r7)),
        "R8": r8,
        "R9": float(len(r9)),
    }
    total = sum(rule_values[code] * weight for code, _, weight in RULES)

    return {
        "total": total,
        "rules": rule_values,
        "details": {
            "source": plan.get("source", ""),
            "rounds": rounds_count,
            "violations": {
                "R1": r1,
                "R2": r2,
                "R4": r4,
                "R5": r5,
                "R6": r6,
                "R7": r7,
                "R9": r9,
            },
        },
    }


def main():
    if len(sys.argv) != 2:
        print("Usage: krcg_score.py plan.json", file=sys.stderr)
        return 2
    with open(sys.argv[1], "r", encoding="utf-8") as handle:
        plan = json.load(handle)
    print(json.dumps(score(plan), sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
