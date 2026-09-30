# The hit vector has to be ON the face

## The bug

Scaffold originally biased its hit point "toward the new block's cell":

```cpp
Vec3 hit{centre.x + normal.x * 0.5, centre.y + normal.y * 0.5, centre.z + normal.z * 0.5};
hit.x -= neighbour.dx * 0.22;    // and y, z
```

`normal` is always exactly `-neighbour.d`, so the first line already places the
point on the neighbour's face. Subtracting `d * 0.22` then moves it a *further*
0.22 along the same normal, off the block entirely. For the block below
(`{Up, 0,-1,0}`) it produced `hit.y = ny + 1.22`.

## Why that matters

`BlockHitResult` sends the cursor as `hit - blockPos`, so the packet carried
`cursorY = 1.22`. Vanilla's server is lenient — it only checks each axis is
within about 1.0000001 of the block centre, and 0.72 passes — so the placement
*worked* and the bug was invisible in play. Two things it does break:

- **Anticheat.** Grim validates the cursor against the clicked block's actual
  collision shape. A cursor outside `[0,1]` is not something a raytrace can
  produce, so every single placement is flaggable.
- **Directional blocks.** Slabs, stairs, trapdoors and buttons resolve their
  placed state from the cursor position on the face. An out-of-range cursor
  resolves to the wrong half or facing.

## The rule

The hit point is the face centre and nothing more:

```cpp
const Vec3 hit{centre.x + normal.x * 0.5,
               centre.y + normal.y * 0.5,
               centre.z + normal.z * 0.5};
```

If jitter is wanted for realism, it must be **tangential** — moved within the
face plane, staying inside `[0,1]` on both in-plane axes — never along the
normal.

## How it was caught

A code review pass over the modules, cross-checked against the game's own
behaviour. It is worth stating plainly that the module *worked* the whole time:
blocks appeared, the player bridged, the smoke test's "player is held above the
shaft" check passed. Only a reviewer reading the geometry found it. Anything
that is only wrong in a way the server tolerates will not show up in a
behavioural test — which is an argument for reviewing packet-shaped code against
the protocol, not just against the outcome.

Related: [[grim-rotation-ordering]]
