# The exact view/projection for 1.21.11

Anything drawn in world space — ESP boxes, block highlights, tracers — has to
use the matrices the frame was actually rendered with, or it swims relative to
the entities it is supposed to be attached to.

## Ground truth from GameRenderer.renderWorld

Read out of the remapped jar with `javap -c` ([[reading-minecraft-ground-truth]]):

```java
Matrix4f proj = getBasicProjectionMatrix(getFov(camera, tickProgress, true));

MatrixStack ms = new MatrixStack();
tiltViewWhenHurt(ms, camera.getLastTickProgress());
if (options.getBobView().getValue())
    bobView(ms, camera.getLastTickProgress());
proj.mul(ms.peek().getPositionMatrix());        // bob folds into PROJECTION
// ... nausea distortion also multiplies into proj ...

Matrix4f view = new Matrix4f().rotation(
    camera.getRotation().conjugate(new Quaternionf()));
```

Two non-obvious details:

1. **View bob lives in the projection matrix**, not the view matrix. Ignoring it
   makes overlays drift by several pixels while walking — the single most
   visible ESP artefact.
2. The view matrix is *rotation only*. World positions are made camera-relative
   by subtracting `camera.getPos()` before the transform.

`getProjectionMatrix(float)` is **not** what you want: it is
`getBasicProjectionMatrix(max(fov, optionsFov))` with no bob.

## bobView in 1.21.11

The old `horizontalSpeed` / `prevStrideDistance` fields are gone. The current
form reads from `ClientPlayerLikeState`:

```java
var state = ((ClientPlayerLikeEntity) client.getCameraEntity()).getState();
float f = state.getReverseLerpedDistanceMoved(tickProgress);
float g = state.lerpMovement(tickProgress);
ms.translate(sin(f*PI) * g * 0.5f, -abs(cos(f*PI) * g), 0);
ms.multiply(POSITIVE_Z.rotationDegrees(sin(f*PI) * g * 3.0f));
ms.multiply(POSITIVE_X.rotationDegrees(abs(cos(f*PI - 0.2f) * g) * 5.0f));
```

`Game::buildBobMatrix` reproduces this verbatim, including the post-multiply
order that `MatrixStack` implies.

## Reading a JOML matrix over JNI

`Matrix4f.get(float[])` writes column-major, which is also OpenGL's order and
what `Mat4` uses, so no transpose is needed. Reuse one global `jfloatArray`
instead of allocating per frame.

`Quaternionf` exposes public `x/y/z/w` fields — four `GetFloatField` calls beat
a method call. The conjugate is just `(-x, -y, -z, w)`.

## What is deliberately skipped

`tiltViewWhenHurt` and the nausea distortion are not reproduced. They only apply
for a fraction of a second after damage or under a status effect, and both cost
several extra JNI calls per frame for a transient effect.

Related: [[fabric-jni-bridge]]
