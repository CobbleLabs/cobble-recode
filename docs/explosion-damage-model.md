# The explosion numbers, and the two errors that made AutoCrystal inert

AutoCrystal placed nothing. Not rarely - never, at any range, in any kit. The
module ran, scanned, scored, and rejected every candidate cell it found. The
cause was the damage model, and it was wrong in two independent ways that both
pushed the self-damage estimate up.

## What vanilla actually computes

From `ExplosionBehavior.calculateDamage` and `ExplosionImpl.damageEntities`
(1.21.11, read with `javap`, see [[reading-minecraft-ground-truth]]):

```
q      = power * 2                              // 12 for an end crystal
s      = distance(entity position, blast) / q
t      = (1 - s) * exposure
damage = (t * t + t) / 2 * 7 * q + 1
```

Two details in there are easy to get wrong and both were:

- **`distance` is measured from the entity's position, not its centre.**
  `Entity.squaredDistanceTo(Vec3d)` reads `getX/getY/getZ`, which is the feet.
  Measuring from `feet + height/2` puts every candidate half a body further
  away than it is.
- **`exposure` is not 1.** It is
  `ExplosionImpl.calculateReceivedDamage(pos, entity)`, the fraction of a ray
  grid across the hitbox that reaches the blast unobstructed. Assuming 1 reads
  a blast at two blocks as 65 damage where it deals about 45.

## Why that made the module inert

Work backwards from the old default `Self limit` of 6 with exposure 1 and no
armour:

```
6 = (t^2 + t) / 2 * 84 + 1   ->   t = 0.113   ->   distance = 10.6 blocks
```

`Place reach` maxes out at 6. There is no cell inside reach that scores under
the limit, so the search always came back empty. The threshold was not tight -
it was unsatisfiable, and had been since the module was written.

Three things fixed it, and all three were needed:

1. Measure from the feet.
2. Compute exposure. Nine rays - eight hitbox corners and the middle - land
   within a few per cent of vanilla's grid and cost nine short raycasts. Doing
   this for every candidate cell would cost hundreds of block lookups a tick, so
   `findSpot` ranks every cell at exposure 1 (wrong in absolute terms, but
   monotonic, so the ordering is right) and then asks the real question about
   the single winner.
3. **Read your own armour.** The self estimate passed `0` for armour points
   while the target estimate passed the target's. The same blast is a death
   sentence naked and a scratch in netherite; `LocalPlayerView` had no armour
   field at all, so one had to be captured.

The estimate still ignores Protection enchantments, so it reads high. That is
the direction a self-damage limit should err in, and it is why the default is
now 10 rather than 6.

## Placement has rules of its own

`EndCrystalItem.useOnBlock` refuses unless all three hold:

- the clicked block is obsidian or bedrock,
- the block above is **air** - `World.isAir`, not `isReplaceable`, so grass
  above obsidian is a refusal,
- `getOtherEntities(null, Box(x, y+1, z, x+1, y+3, z+1))` is **empty**, and the
  placing player counts.

That last one is the one that bites. The highest-damage cell is almost always
the one under the target's feet, so an unguarded search picks it every time and
every use packet is refused - silently, with no chat message and nothing in the
server log, for as long as the target stands there. The symptom is a module
that looks like it is working, logs a placement, and never spawns a crystal.

Related: [[grim-rotation-ordering]], [[reading-minecraft-ground-truth]].
