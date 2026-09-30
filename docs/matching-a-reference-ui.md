# Rebuilding a menu 1:1 from a screenshot

The ClickGUI and the HUD are ports of a reference client's, matched against two
screenshots. Guessing at the numbers wastes a build cycle per guess; measuring
them takes one script.

## Measure, do not eyeball

`stb`-free PNG decode in ~60 lines of node (inflate + the five filters) is
enough to sample the reference and our own `shot` output the same way:

- **runs of identical colour along a row/column** find panel edges, card edges,
  gradients and the exact fill colours;
- **ink bounding boxes** over a luminance threshold give text position and, more
  importantly, text *height*, which is what a font size has to be solved from;
- the same two functions run over `build/shots/*.png` turn "is it right yet"
  into a number.

Both screenshots were at Minecraft's GUI scale 2, so every measurement divides
by two and lands in GUI-scaled units.

## Work in Minecraft's own units

The reference window is 400x300 **scaled** units, not pixels. The payload draws
in framebuffer pixels, so the whole menu is laid out in units and multiplied by

```
unit = windowWidth / scaledWidth   (that is Minecraft's own scale factor)
```

which is what makes the menu the same size on screen as the vanilla interface
at any resolution, and makes a screenshot line up with the reference. The
window position is stored in units too, so a resolution change carries it.

The open animation scales `unit` about the window centre, so one multiplier
animates the whole thing without a transform stack.

## A font size has to mean an em

`stbtt_ScaleForPixelHeight` normalises to ascent-descent, so a "22 px" Inter
glyph came out with a 13 px cap height instead of 16 - every label 21% small,
and the mono face wrong by a different factor. `FontFace::atlasPixelHeight_` is
now the em in atlas pixels (`atlasPixelHeight * scale / stbtt_ScaleForMappingEmToPixels`),
so `size` means what a design tool means by it, and sizes solved off the
reference can be used directly.

With that, the reference's ink heights convert straight into sizes (Inter: cap
0.727 em, ascender 0.75, descender 0.21):

| element | reference ink | size |
| --- | --- | --- |
| window title | 24 px ascender | 16 |
| module name | 16 px cap | 10.3 |
| description | 14 px asc+desc | 7.3 |
| setting label | 12 px ascender | 8.2 |
| sidebar label | 13 px ascender | 8.7 |
| HUD watermark | 26 px ascender | 17.3 |
| HUD array list | 13 px ascender | 8.7 |

## Place text by its cap line

A design measures from the cap line, and the reference's rows are aligned that
way. `FontFace::capTop` (from the `H` glyph's SDF offset, minus the padding the
rasteriser adds) plus `Renderer::textCap` puts a run where the reference has it
regardless of size, which is why the name, the tag beside it and the
description all land on their measured rows to within half a unit.

## Colours came out exactly

Panel `#171A21`, cards black at 50/255 over it (`#12151B`), rail `#1A1C21`
falling to `#14161B`, accent `#E2D0F9`, its darker step for the selected pill,
white at 220/200/70/64 for the four text weights. Two mistakes cost a cycle
each:

- `Color::withAlpha` **multiplies**, so `surface.withAlpha(surface.a * alpha)`
  squared the 0.196 into 0.038 and the cards vanished into the panel;
- the slider track and the empty boolean dot are the *window* colour, lighter
  than the card they sit on. Drawing them in the darker secondary made them
  invisible, and a slider with no track reads as a stray dot.

## Driving it without a mouse

`shot` cannot click, so the control channel grew `screen <name>` and
`pick <module> on|off` beside `expand`. Every screen, an expanded card and the
colour popup are then one batch each ([[build-and-test]]).

## Euphoria dropdown port notes

- Euphoria sizes are scaled pixels, so the constants transfer 1:1 into cold's
  GUI units (`140` panels, `16` header, `12` rows, `sf 18` ≈ `9.0` units).
- Six `140` panels need `916` scaled px; below that they shrink to fit rather
  than hanging off-screen (`ClickGui::panelW_`).
- A close animation that targets `closing ? 0 : 1` fades the menu back in after
  it finishes; the target must be `open && !closing`.
- TargetInfo is verifiable without input: `cmd summon` a `{NoAI:1}` pig onto
  the sightline (computed from `state` pos/yaw/pitch) and shoot in the same
  batch — TriggerBot publishes it to `CombatTarget` before the second swing.
- ArrayList rows are placed by one origin: if the layout stores the text's left
  edge, the draw must start the text there too. Drawing it as `x - width`
  (the old right-edge convention) hangs every row a full width left of its
  background, and narrow rows hide the mistake.
