# Puzzle Extension

![Java Version](https://img.shields.io/badge/Java-21-orange)
![Build Status](https://img.shields.io/badge/build-passing-brightgreen)
![Target](https://img.shields.io/badge/Target-Paper%20/%20Folia-blue)

**Puzzle Extension** adds nine reusable puzzle objectives to **TypeWriter**. Each puzzle renders
its board client-side, tracks progress per player, and drives the usual TypeWriter surface: facts,
triggers and objective chains.

The public build uses only official TypeWriter and Paper APIs. It never mutates the world: every
visual is a packet sent to one player, and the real blocks stay untouched.

---

## Puzzle types

| Entry | What the player does |
| --- | --- |
| `puzzle_block_push` | Pushes blocks into target holes, Sokoban style. |
| `puzzle_memory` | Watches a highlighted sequence, then repeats it. |
| `puzzle_laser_grid` | Rotates mirrors until each beam reaches its receiver. |
| `puzzle_lever_order` | Flips levers in the configured order. |
| `puzzle_pattern` | Places blocks to reproduce a displayed pattern. |
| `puzzle_coop_pressure` | Several players hold pressure plates at the same time. |
| `puzzle_item_frame` | Fills an item frame, then rotates it to the target angle. |
| `puzzle_combination_lock` | Presses buttons in the order of a code. |
| `puzzle_pedestal` | Offers a required item on a pedestal. |

Three support entries complete the surface: `puzzle_chain` (complete a set of puzzles),
`puzzle_artifact` (persistent progress and statistics) and `puzzle_menu` (an in-game menu listing
the configured puzzles).

## Commands

Puzzle commands live under TypeWriter's own command tree:

```
/typewriter puzzle
/typewriter puzzle status
/typewriter puzzle start <entry>
```

There is no standalone `/puzzle` command.

## Shared options

Every puzzle entry shares the objective options below.

| Option | Meaning |
| --- | --- |
| `amount` | Number of steps the puzzle counts as progress. |
| `timeLimit` | Seconds before the puzzle times out. `0` disables it. |
| `cooldownOnFail` | Seconds the player must wait after a failure. |
| `maxAttempts` | Wrong attempts allowed **per session**. `0` disables the limit. |
| `resetOnWrong` | Whether a wrong step clears the progress. |
| `showHints` / `hintMessage` | Progress hints, with `{progress}` and `{total}` placeholders. |
| `completionMessage` | Message sent once the puzzle is completed. Blank disables it. |
| `onStart` / `onComplete` / `onFail` / `onTimeout` / `onLifespanExpire` | TriggerableEntry refs. |
| `fact` | Fact incremented by `amount` on completion. |
| `lifespan` | Seconds before the puzzle expires regardless of activity. |
| `isShared` | Whether the puzzle state is shared by the audience. |
| `activationRadius` | Detection radius in blocks around the puzzle positions. `0` keeps the world check only. |

## Behaviour worth knowing

**A puzzle belongs to one world, and to the ground around it.** Being in the audience is not
enough: the puzzle only renders and runs for a player standing in the world of its own positions,
within `activationRadius` blocks of at least one of them. This is not cosmetic — a client-side
block or a particle carries coordinates and no world, so without the gate a board configured in one
dimension was drawn in every dimension the player visited.

**A session starts when the player reaches the puzzle.** Arriving clears the progress, the
attempt budget and the failure cooldown, and arms `timeLimit` and `lifespan`. `maxAttempts` is
therefore a per-session budget, not a lifetime one — a player who exhausted it simply restarts the
puzzle. Walking away clears the board on the client and cancels the pending animations. A puzzle
that is already solved is never reopened, so completion stays idempotent.

**Completion fires once.** `completionMessage`, the fact write, `onComplete` and the chain progress
are all emitted from a single accepted transition, so a duplicated interaction event cannot trigger
them twice. The message goes to the player who completed the puzzle.

**Client-side visuals are replayed, not assumed.** A block change sent to a client that has not
received the chunk yet is discarded, and a chunk delivered afterwards carries the real world state.
The renderer tracks what it sent and replays it after chunk delivery, after a respawn or world
change, and shortly after any render burst. Without that replay a board is registered server-side
but invisible in game until an unrelated block update refreshes the section.

**Coop pressure requires real players.** `requiredPlayers` counts distinct `Player` entities on
distinct plates. A dropped item standing on a plate never satisfies the requirement — this is
deliberate, not a limitation.

**Item frames are driven, not created.** `puzzle_item_frame` expects an item frame to already exist
at the configured position. An empty frame only accepts the required item; a filled frame only
rotates, one step per interaction, and never asks for the item again. If no frame exists at the
position the player is told, and the position is logged as a warning.

## Requirements

- Paper or Folia, Minecraft 1.21.x
- Java 21
- TypeWriter engine `0.9.0-beta-177`

## Documentation

Full documentation available at [BTC Studio Docs](https://docs.borntocraftstudio.net/extensions/free/puzzle/).

## Building

```bash
./gradlew :TypeWriter-PuzzleExtension:build
```

The extension jar is produced in `build/libs/`.

---

## 📜 Licence

**GNU General Public License v3.0 or later** — [LICENSE](LICENSE) — with a
**linking exception** for the Typewriter engine — [LICENSE-EXCEPTION.md](LICENSE-EXCEPTION.md).

| | |
|---|---|
| You may | Run it anywhere, **including on a monetised server**. Study it, modify it, use it as a base, and redistribute it — **even for a fee**. GPLv3 §4 explicitly allows charging for a copy. |
| You must | Publish the complete corresponding source of your version under GPLv3, preserve the copyright notices, and **state that you modified it and when** (§5(a)). |
| You may not | Ship a closed-source or proprietary version, relicense under stricter terms, or strip the attribution and present this work as your own — §8 terminates your rights automatically. |
| Marks | **"Born To Craft"** and **"BTC Studio"** are **not** covered by the GPL. Fork it freely, sell your fork if you like — but **rebrand it**. |

> Reselling this code is legally allowed and practically pointless: whoever buys a
> copy from you receives, under the GPL, the right to redistribute it for free.
> That is the protection — not a clause forbidding sale, which the GPL does not
> permit us to add.

### About Typewriter

This is a **third-party extension**. It uses the public extension API of the
[Typewriter](https://github.com/gabber235/Typewriter) engine by gabber235 and
contains none of its source. Born To Craft Studio is not affiliated with or
endorsed by the Typewriter project.

The engine itself is **not** free software — its licence forbids redistributing
it. **Get it from the Typewriter project, and never redistribute it**, including
inside a fork of this repository.

Full attribution, the statement of modifications required by §5(a), and the
trademark reservation are in **[NOTICE.md](NOTICE.md)**. Read it before
redistributing.

© 2026 Born To Craft Studio.
