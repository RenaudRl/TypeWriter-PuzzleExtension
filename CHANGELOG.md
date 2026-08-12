# Changelog

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
