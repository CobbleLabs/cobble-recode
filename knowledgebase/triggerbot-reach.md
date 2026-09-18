# TriggerBot reach flags

## The symptom
Intermittent "suspected reach" flags against GrimAC, a few per session, while
the crosshair never visibly left the target.

## Two causes, both in the strike path

### 1. The sprint-reset pending path struck on stale geometry
`sprintReset_` holds a hit back one tick to drop sprint (a w-tap). The pending
path then struck the entity it remembered by id, checked only `acceptable` and
`timingAllows` — never the distance again. One tick of a sprinting target is up
to a third of a block, and it can only move away. That is exactly an
intermittent reach flag, which is why it fired rarely: only when the target
moved outward during the one held-back tick. Direct mode could flag on this;
vanilla mode could not (the game re-picks at its own moment).

Fixed by re-picking in the pending path and only striking when the fresh pick
is still the same entity. Bonus: a target that stepped behind a wall in the
meantime is no longer struck either.

### 2. The acceptance distance capped at the Range setting, not at vanilla
The Range slider (up to 6.0) fed `pick()` directly, so a strike could be
accepted at 4–6 blocks — far past vanilla's 3.0 — and Grim flags that
deterministically. The server scores the attack against its own view of the
hitbox, which is never closer than the client's.

Fixed by clamping the acceptance radius to **2.95** (a hair under 3.0 for
interpolation) in `pick()`. Range still widens what the module *looks* at and
what ESP/target-hud consider; it can never widen what it attacks.

## Verified against the live GrimAC server (2026-09-08)
- Full smoke suite: 20/20, TriggerBot hit at ~2.0 blocks, 0 Grim alerts.
- Reach ladder (Range 5.0, Module any):
  - 2.0 blocks → hits (True)
  - 5.1 blocks → refuses (False) — the old code attacked here
  - 2.8 blocks → hits (True) — near-limit acceptance preserved
- Test gotchas: "first hit on entity" logs once per module **enable**
  (`hits_ == 1`), so toggle the module off/on between phases; an armor stand
  has 20 HP and dies to the phase-A strikes, so re-summon it (Invulnerable:1b)
  per phase.

## Build for the test loop
`tools/dev.sh build` builds the local-only payload into `dist/`. Smoke runs
against `dist/`; there is no auth and no key file in this build.
