# One shader, one draw call, crisp at every size

## Shape

Every primitive the client draws — panels, outlines, shadows, glows, text,
icons, ESP boxes, sliders — is a quad carrying its own signed-distance
parameters:

```
vec2 aPos      screen pixels
vec2 aUv       atlas coordinates
vec4 aColor    per-vertex, so linear gradients are free
vec4 aRect     centre.xy, half extents.zw, in pixels
vec4 aStyle    corner radius, spread, mode, mode-specific parameter
```

The fragment shader branches on `mode` and evaluates the field per pixel. A
whole frame of UI, HUD and ESP therefore collapses into one `glDrawElements`
unless a clip rectangle or an external texture forces a break. Two texture
units hold the font and icon atlases for the entire run, so they never need
rebinding.

## The one-pixel antialiasing rule

The obvious formula is wrong and it cost a debugging round:

```glsl
float w = fwidth(d);
float alpha = smoothstep(0.5 - w, 0.5 + w, d);   // ramp is TWO pixels wide
```

`fwidth(d)` is already the change in the field per screen pixel, so a window of
±fwidth spreads the edge over two pixels. At 12 px UI text that is most of the
glyph, and everything reads as a blur. The analytic coverage is:

```glsl
float alpha = clamp((d - 0.5) / max(fwidth(d), 1e-5) + 0.5, 0.0, 1.0);
```

which ramps over exactly one pixel. Same idea for the shape SDF, where the field
is a distance rather than a 0..1 sample:

```glsl
float alpha = clamp(0.5 - d / max(fwidth(d), 1e-5), 0.0, 1.0);
```

## Atlases

- **Text**: all six faces share one 2048² R8 atlas built with
  `stbtt_GetCodepointSDF` at 52 px, 6 px spread, shelf-packed tallest first.
  One atlas means a line mixing Inter and JetBrains Mono still batches.
- **Icons**: Lucide SVGs parsed with nanosvg, rasterised at 4x, converted with
  an exact Felzenszwalb distance transform, stored as 64 px tiles.

Both encode 128 as the edge so they share the shader path.

Watch the **sign**: the signed field must be positive *inside* the shape. With
one buffer holding the distance to the nearest solid pixel and another the
distance to the nearest empty one, it is `outside - inside`. Getting it
backwards fills the whole tile and punches the glyph out of it, which looks like
an icon on a coloured chip.

## Uploading

See [[gl-state-restore]] for the pixel-transfer state that has to be neutralised
first — this is where a correct atlas becomes a corrupt texture.

## Costs

Measured in the dev client at 1280x720 with the menu open, the ESP running over
80 entities, and the HUD drawing: the whole overlay pass is reported by the
control channel's `state` command as `overlay=<n>us`. Keeping it in one batch is
what makes that number small; the expensive parts are the JNI reads, not the
drawing.
