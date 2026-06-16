# tournament-seating

Command-line tournament seating solver for Vampire: The Eternal Struggle preliminary rounds.
The project uses Quarkus, Java 21, and Timefold Solver to generate table seating plans that optimise
predator/prey relationships, table repeats, seat positions, VP access, starting transfers, and sit-out fairness.

## Current functionality

- Generates seating plans for a requested player count and number of rounds.
- Tries every valid table layout for the player count in parallel and prints the best Timefold score.
- Uses only real 4-player and 5-player tables; players who cannot be seated in a round sit out the whole round.
- Adds extra rounds in normal solver mode when needed so sit-outs can be distributed evenly.
- Supports fixed-round comparison mode for apples-to-apples checks against Archon seating data.
- Imports Archon plans from `thearchon1.5l.xlsx`.
- Scores both Archon and generated plans with the bundled KRCG-compatible scorer at `tools/krcg/krcg_score.py`.
- Runs batch comparisons across player ranges and writes resumable state plus a Markdown report of confirmed superior cases.

## Prerequisites

- Java 21.
- Python 3 for KRCG comparison scoring.
- Maven is supplied through `./mvnw`.

## Run the solver

Run from source in Quarkus dev mode:

```shell
./mvnw quarkus:dev -Dquarkus.args="--players=10 --rounds=3 --time=60s"
```

Build and run the packaged application:

```shell
./mvnw package
java -jar target/quarkus-app/quarkus-run.jar --players=10 --rounds=3 --time=60s
```

Arguments:

- `--players=N`: number of players. Minimum is 4.
- `--rounds=N`: requested number of rounds. Minimum is 1.
- `--time=X`: solver time budget per layout, for example `30s`, `5m`, `1h`, or an ISO-8601 duration such as `PT60S`.

Normal solver mode may add extra rounds when the selected layout has sit-outs and the requested round count cannot
distribute those sit-outs equally across all players.

## Compare with Archon and KRCG

Run one comparison case:

```shell
./mvnw package
java -jar target/quarkus-app/quarkus-run.jar compare --players=11 --rounds=3 --time=5m
```

Optional paths:

```shell
java -jar target/quarkus-app/quarkus-run.jar compare \
  --players=11 \
  --rounds=3 \
  --time=5m \
  --archon=thearchon1.5l.xlsx \
  --krcg-script=tools/krcg/krcg_score.py
```

Comparison mode supports `--rounds=2` or `--rounds=3`. It imports the matching Archon seating plan, solves a
Timefold plan with the same number of seated players per round, scores both plans with Timefold and KRCG, and prints
the seating plans plus per-rule KRCG deltas.

Run a resumable batch comparison:

```shell
java -jar target/quarkus-app/quarkus-run.jar batch-compare \
  --min-players=8 \
  --max-players=40 \
  --rounds=2,3 \
  --initial-time=5m \
  --step=5m \
  --cycles=1
```

Batch options:

- `--min-players=N` and `--max-players=N`: inclusive player range.
- `--rounds=2,3`: preliminary round counts to test. Only `2` and `3` are supported.
- `--initial-time=X`: first solver budget for each case.
- `--step=X`: extra budget added after a case is not confirmed.
- `--cycles=N`: number of passes over pending cases. Use `--cycles=0` to continue until all cases are confirmed.
- `--state=PATH`: resumable JSON state file. Defaults to `comparison-batch-state.json`.
- `--report=PATH`: Markdown report. Defaults to `confirmed-superior-report.md`.
- `--archon=PATH` and `--krcg-script=PATH`: override comparison inputs.

## KRCG comparison and rule differences

The bundled KRCG scorer implements the public KRCG-style weighted rule order:

| Rule | Meaning |
| --- | --- |
| R1 | Repeated predator/prey relationship |
| R2 | Pair shares a table in every preliminary round |
| R3 | Available VP balance |
| R4 | Pair shares a table more than once |
| R5 | Player sits in seat 5 more than once |
| R6 | Repeated relative position |
| R7 | Repeated absolute seat position |
| R8 | Starting transfer balance |
| R9 | Repeated adjacent/non-neighbour position group |

How to read KRCG comparison output:

- KRCG is a penalty score, so lower is better.
- `KRCG total` is the weighted sum of all rule values. The weights are intentionally very large for high-priority
  rules, so one R1 violation dominates any number of lower-priority improvements.
- `KRCG delta` is `Timefold total - Archon total`. A negative delta means the Timefold seating is better under KRCG;
  a positive delta means Archon is better under KRCG.
- In the per-rule table, each value is the raw unweighted rule value before the rule weight is applied. For count-based
  rules such as R1, R2, R4, R5, R6, R7, and R9, this is the number of violations. For R3 and R8, this is a standard
  deviation, so smaller means better balance.
- The per-rule `Delta` is `Timefold rule value - Archon rule value`. Negative is better for Timefold, positive is worse,
  and zero means both plans are tied for that rule.
- Rule priority matters more than the count of rows won. For example, improving R4 by `-1.0000` is worth `1,000,000`
  points, while worsening R8 by `+1.0000` costs only `100` points.

Example report lines:

```text
KRCG Timefold total: 3000008.1067
KRCG Archon total: 4000065.3538
KRCG delta: -1000057.2471
```

This means the Timefold plan is better under KRCG by about 1,000,057 weighted penalty points. If the per-rule table
shows `R4` with a Timefold delta of `-1.0000`, most of that improvement comes from avoiding one repeated-opponent
violation at the R4 priority level.

The Timefold constraints intentionally mirror that ordering with hard, medium, and soft score levels, but the project
differs from KRCG/Archon in a few important places:

- Normal solver mode can add extra rounds to equalise play counts when a layout requires sit-outs. Comparison mode does
  not add rounds, because it must compare against fixed 2-round or 3-round Archon schedules.
- VP access and starting transfers are balanced by played-round ratios in Timefold. This keeps players who sat out from
  being unfairly compared as if they had the same number of games as active players.
- The solver evaluates multiple valid table layouts for a player count and chooses the best score. This can beat a fixed
  Archon layout when another legal layout gives better repeat avoidance or transfer balance.

The main justification for the project rules is that they optimise the same tournament fairness goals while avoiding
ambiguous seating artifacts. Whole-round byes, ratio-based balance, and layout search produce schedules that are easier
to explain to players and can be directly re-scored by KRCG for external validation.

Confirmed comparison wins are written to `confirmed-superior-report.md`.

## Update and maintenance commands

Run a compile/package check:

```shell
./mvnw package
```

Run the Quarkus update helper after changing Quarkus versions or extensions:

```shell
./mvnw quarkus:update
```

Update dependency versions in `pom.xml`, then rebuild with `./mvnw package`. The important runtime versions currently
declared in `pom.xml` are Quarkus `3.35.2`, Timefold Solver `2.0.0`, and Java release `21`.

Generated comparison state and reports are normal project files:

- `comparison-batch-state.json`: resumable batch progress.
- `confirmed-superior-report.md`: Markdown report of cases where Timefold beats Archon under both Timefold and KRCG scoring.

## Native build

Build a native executable:

```shell
./mvnw package -Dnative
```

Or build native in a container if GraalVM is not installed locally:

```shell
./mvnw package -Dnative -Dquarkus.native.container-build=true
```

Run the native executable:

```shell
./target/tournament-seating-1.0-SNAPSHOT-runner --players=10 --rounds=3 --time=60s
```
