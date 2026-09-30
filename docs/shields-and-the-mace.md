# Only an axe opens a shield, and a mace is not an axe

A mace smash into a raised shield does nothing at all. Not reduced damage -
nothing, including the knockback wave people expect to land anyway.

`Items.MACE` registers with `new WeaponComponent(1)`, the single-argument
constructor, which leaves `disableBlockingForSeconds` at zero. Only
`WeaponComponent.AXE_DISABLE_BLOCKING_FOR_SECONDS` (5.0) is non-zero in
vanilla, and it belongs to axes.

The chain that makes this total rather than partial:

```
PlayerEntity.attack ->  boolean landed = target.damage(...)
                        if (landed) stack.postHit(...)
```

A blocked hit returns false out of `damage()`, so `postHit` never runs, and
`MaceItem`'s area knockback lives inside `postHit`. Spending a sixteen-block
fall on a shielding target buys exactly zero.

So AutoMace opens the shield first: swap to an axe, one hit, swap to the mace,
smash. Three things that were not obvious while building it:

- **The axe hit has to go in early in the fall, not at the bottom.** Opening a
  shield costs a hit, a swap in and a swap back; there is no room for any of
  that on the last airborne tick. Gate it on the same `Drop` threshold as the
  smash and it lands with ticks to spare - but gate it on *nothing* and
  stepping off a one-block ledge near anyone holding a shield sends an axe
  swing that no smash can ever follow.
- **Use a retry timer, not a once-per-fall flag.** The disable is applied
  server side, so the client only learns the shield came down a round trip
  later. A flag that latches means a target who re-raises inside the same fall
  is never opened again and the fall is spent on nothing; no gap at all means
  the whole fall goes into swinging an axe at a shield the server already
  opened. 300 ms between attempts covers both.
- **Every path that stops short of a smash has to hand the borrowed slot
  back.** Lose the target one tick after the axe hit and the axe stays
  selected for the rest of the fight, and the eventual restore writes a slot
  index recorded several encounters ago.

Reading a shield from the client is `LivingEntity.isBlocking()`, one call per
captured entity.

Related: [[grim-rotation-ordering]], [[reading-minecraft-ground-truth]].
