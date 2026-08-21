# Changelog

## 0.2 — 2026-08-20

Puzzles are now bound to the place they were built.

- **New field on every puzzle: `activationRadius`** (default `16` blocks). A puzzle only renders
  and runs for a player standing in the world of its own positions, within that radius of one of
  them. `0` disables the distance check only — the world check always applies.
- **Fixed: a puzzle was drawn in every dimension.** Client-side blocks and particles carry
  coordinates but no world, so a laser grid configured in the Overworld was painted in front of the
  player in the Nether, the End, or any other world they visited. The laser trace now stops
  outright when the player is not in the world of the emitters.
- **Fixed: a puzzle started as soon as the player joined its audience**, whatever the distance.
  A memory sequence could play its whole pattern to someone thousands of blocks away, or in
  another world. The sequence now starts when the player reaches the board.
- Walking away from a puzzle clears its board on the client and cancels its pending animations;
  coming back starts a fresh session. The attempt budget, the failure cooldown, `timeLimit` and
  `lifespan` are now armed on arrival instead of on audience join.

## 0.1 — 2026-08-12

First release.

- **Nine puzzle types**, each a Typewriter objective with its own audience, progress and triggers:
  `puzzle_block_push`, `puzzle_pedestal`, `puzzle_memory`, `puzzle_lever_order`, `puzzle_pattern`,
  `puzzle_item_frame`, `puzzle_combination_lock`, `puzzle_laser_grid`, `puzzle_coop_pressure`.
- Everything a puzzle draws is **client-side** — blocks, ghost previews, highlights and laser
  beams are sent to the player solving it. Two players can work the same board without seeing each
  other's progress, and the world is never modified.
- Shared lifecycle on every puzzle: `timeLimit`, `maxAttempts`, `cooldownOnFail`, `resetOnWrong`,
  `lifespan`, hints, and `onStart` / `onComplete` / `onFail` / `onTimeout` / `onLifespanExpire`.
- `isShared` switches a puzzle from private progress to one board the whole group advances
  together.
- `puzzle_chain` runs several puzzles in order and fires once the sequence is solved; `resetOnFail`
  decides whether a mistake sends the player back to the start.
- `puzzle_artifact` persists puzzle state, so a restart mid-attempt does not erase progress.
- `puzzle_solved_fact` and `puzzle_active_fact` expose progress to the rest of your content;
  `puzzle_menu` lists puzzles and their state in chat.
- Folia-safe: every world read and write runs on the region owning the puzzle.
