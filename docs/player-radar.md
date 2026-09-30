# Reading another player, and drawing them back

The Radar window shows everything the client already knows about the other
players on a server: name, ping, distance, health, the six equipment slots with
their enchantments and wear, active effects, and a running history of every item
that has passed through those slots. It draws the skin as a real model you can
turn.

None of that asks the server for anything. Equipment arrives in
`EntityEquipmentUpdate` because the client has to render it; the skin has
already been downloaded and uploaded; the item sprites were stitched at load.
The work is entirely in reaching them from a native payload, and four of the
steps are not obvious.

## The GL name of a texture has no mapping any more

Since 1.21.5 a Minecraft texture is a `GpuTexture`, an abstract handle. The
OpenGL implementation that actually owns the `glGenTextures` name is **not in
the client jar** and carries no Yarn mapping, so there is nothing to put in
`mappings.def.mjs` and nothing to call.

What is available is `AbstractTexture.getGlTexture()`, which hands back an
instance of that unmapped class. From there the name is found by shape rather
than by name:

1. Walk the concrete class **and its superclasses** with
   `Class.getDeclaredFields`, collecting every `int` field. The hierarchy walk
   is the part that is easy to miss - `getDeclaredFields` reports one class at a
   time, so the implementation's GL name and the base class's width and height
   never appear in the same call.
2. Drop the static ones. `getDeclaredFields` returns statics too, and
   `GetIntField` on a static is undefined - it treats the static's slot as an
   offset into the instance. It reads whatever is there, and the first version
   of this took the game down with an access violation on the first frame.
3. `FromReflectedField` each survivor into a `jfieldID` and read it. JNI field
   access does no access control, so nothing has to be `setAccessible`.
4. Identify the right one: bind each candidate value, ask GL for the bound
   texture's width and height, and keep the field whose real dimensions are
   themselves sitting in two of the *other* candidate fields. A texture object
   and the two ints describing it are all in the same object, so the check needs
   no names at all and survives the class being obfuscated.

Test 4 is what makes this safe. `glIsTexture` alone is not enough: a width of
64 is a perfectly plausible live texture name in a running Minecraft.

Cache the `jfieldID` - there is one implementation class for every texture in
the game - and retry rather than give up if a texture has not finished being
created, because a texture with no dimensions cannot be matched yet.

## The atlas manager is keyed by definition, not by texture

`SpriteAtlasTexture.ITEMS_ATLAS_TEXTURE` is the identifier of the *stitched
texture* (`minecraft:textures/atlas/items.png`). `AtlasManager.getAtlasTexture`
looks up `entriesByDefinitionId`, which holds `minecraft:items` - the value in
`net.minecraft.util.Atlases`. They are different identifiers and the wrong one
does not return null:

```java
Entry entry = this.entriesByDefinitionId.get(id);
if (entry == null) throw new IllegalArgumentException(...);
```

So the failure arrives as a Java exception that `checkException` clears, and the
call looks like it quietly returned nothing. `Atlases.ITEMS` and
`Atlases.BLOCKS` are the keys.

Once the atlas is in hand, `getSprite(Identifier.of("minecraft:item/<path>"))`
gives `getMinU/getMaxU/getMinV/getMaxV`, and the atlas is itself an
`AbstractTexture`, so its GL name comes from the same routine as the skin's.

**Telling a real sprite from the missing one** matters, because a block item has
no flat sprite in the item atlas - the game builds its icon from a model - and
`getSprite` answers with the missing texture rather than null. Look up a name
the pack cannot possibly hold once (`item/cold_wtf_absent`), remember its uv
rectangle, and compare. Anything that matches it falls through to the block
atlas, and anything that matches there too falls back to a lucide glyph chosen
from the registry path.

## The skin is six boxes and a painter's sort

The renderer has one primitive that takes four arbitrary screen points
(`face()`), and one that samples a texture into an axis-aligned rectangle
(`image()`). A rotating player model needs both at once, so `imageFace()` is the
two put together: `submit()` already carries four explicit corners and a uv
rectangle, and `MODE_IMAGE` already ignores the corner geometry, so the only
new part is inflating the sdf box past the polygon the way `face()` does, which
makes the rounded-box coverage read as fully covered inside it.

Geometry, in skin pixels with the feet at zero: legs 4x12x4 at y 0..12, body
8x12x4 and arms 4x12x4 (3 wide for a slim skin) at 12..24, head 8x8x8 at 24..32.
The overlay layer is the same box grown by half a unit. The uv cross for a box
of `w x h x d` at origin `(ox, oy)` is, along the middle row, `right(d), front(w),
left(d), back(w)`, with `top(w x d)` and `bottom(w x d)` above them - and
"right" means the *entity's* right, which is the viewer's left when the model
faces them.

Projection is orthographic. Perspective on a figure this size buys nothing and
costs the straight edges that make it read as pixel art. Two things then do all
the hidden-surface work:

- **Winding.** With screen y downward, a face listed top-left, top-right,
  bottom-right, bottom-left as seen from *outside* the box has positive signed
  area. A face turned away winds the other way, so `area <= 0` is the whole
  back-face test.
- **Painter's order.** The batch has no depth buffer, and boxes overlap each
  other - an arm crosses the body - so the surviving faces are sorted by
  centroid depth and drawn far to near. Overlay faces get a small bias toward
  the camera so they never fight their own base layer.

Twelve boxes at six faces each is 72 quads before culling, which is nothing next
to a frame of menu.

## Keying the history by UUID, not by entity id

An entity id is recycled the moment a player walks out of render distance, so a
record keyed by one will eventually attach somebody else's sword to somebody
else's name. `Entity.getUuidAsString` is the key; the entity id is cached
against it only to keep the per-frame walk free of JNI, and that cache is
dropped for any id whose player is no longer loaded.

Six equipment reads with their enchantment sets is about thirty JNI calls, so
forty players every tick would be twenty-four thousand calls a second for
information that changes once a minute. The window's selection is refreshed
every tick and everybody else takes turns, a few per tick, from where the last
tick stopped.

## What it cannot see

Only the six slots the server sends: head, chest, legs, feet, main hand, off
hand. The rest of a hotbar is invisible until its owner holds it - which is
exactly why the history is worth keeping, because over a minute of fighting most
of it passes through the main hand.

## Related

- [[fabric-jni-bridge]] — how the mapping table reaches these symbols.
- [[sdf-ui-pipeline]] — the batch `imageFace` writes into.
- [[gl-state-restore]] — the state a borrowed texture bind has to put back.
- [[reading-minecraft-ground-truth]] — `javap` on the remapped jar, which is how
  the `getAtlasTexture` key was found rather than guessed.
