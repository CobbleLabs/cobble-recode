# Telly is a cycle, not an angle

The first attempt held pitch 84 and left yaw alone, all the time. That is one
line of Rise's `getTellyRotation` and none of the machinery around it, and it
does not bridge. Rise's `Scaffold`, `case "Telly"`, runs three phases:

| Phase | Yaw | Pitch | Speed |
| --- | --- | --- | --- |
| Wind-up (on the ground, moving) | untouched | 85 + rand | 10, so it drifts down |
| Jump tick | direction of travel | 85 + rand | 180, a snap |
| Airborne | 45 degree grid, raycast picked | ~82 | fast |

The jump tick is the interesting one. Snapping the yaw onto
`MoveUtil.direction()` is not cosmetic: an anticheat that predicts movement
brute-forces the input combinations against the rotation it was sent, and
"holding W at the direction of travel" is a combination that reproduces the
observed velocity exactly. An arbitrary yaw is not, which is why **Normal with
silent rotations while moving is the flaggable mode and telly is not.**

`tellyJumpDelay` is re-rolled from a range on every landing (`age.wv()`), and
the jump fires on the ground tick that matches it, so the rhythm is never the
same two jumps running.

## The mistake worth remembering: which way round the search goes

The obvious design is to pick the cell to build in, then look for a rotation
that reaches it. That fails, and the failure is quiet - the module sits there
logging `no raycast, aiming direct` forever.

At telly pitch the ray leaves the eye at 1.62 above the feet and comes down
almost vertically; by the time it reaches foot level it has travelled about
0.2 blocks horizontally. So the only face it can ever see is the **top** face of
whatever is directly under the player. A cell chosen 0.65 behind the player -
which is the right cell to build in - is a cell no telly rotation can look at.

Rise does it the other way round, and checks `mc.objectMouseOver.getBlockPos()`
against the block it meant to click. So:

```
for each yaw on the 45 grid around the player and their movement:
  for each pitch near 82:
    hit = raycast(eye, look(yaw, pitch), reach)
    cell = hit.block + normal(hit.face)
    if cell is replaceable and cell.y < floor(feet.y):
       candidate
```

The placement falls out of the ray. The rotation and the block then agree by
construction, which is the property that matters: the server runs its own
raycast from the rotation in the packet, and it has to land on the same face
the click names.

Bridging is done **walking backwards**. The block goes ahead of the movement and
behind the view, so the direction of travel is what the anchor is measured
against, and `MoveUtil.direction()` is roughly `playerYaw + 180`.

## Cost

Four yaws by seven pitches is 28 rays a tick, and the rays all start at the same
eye and overlap almost completely. Without a cache that is a few hundred JNI
`getBlockState` calls per tick. A 1024-slot direct-mapped cache on
(x, y, z), cleared once per tick, brings the whole search down to roughly the
cost of one ray. See `game/Raycast.cpp`.

Related: [[block-placement-hit-vector]], [[scaffold-placement-search]],
[[silent-rotations-without-bytecode]], [[grim-rotation-ordering]]
