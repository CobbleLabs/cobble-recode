# One mapping table for dev and production

## Problem

A native client injected into Fabric has to name Minecraft's classes, but the
names differ by environment:

| Environment | Runtime namespace | `MinecraftClient` is called |
| --- | --- | --- |
| Loom `runClient` workspace | `named` | `net.minecraft.client.MinecraftClient` |
| Shipped Fabric install | `intermediary` | `net.minecraft.class_310` |

Hard-coding either one means the client works in exactly one of the two places,
and testing becomes impossible in the environment you do not target.

## Solution

Author everything against **intermediary**, and let Fabric translate at startup:

```java
FabricLoader.getInstance().getMappingResolver()
    .mapClassName("intermediary", "net.minecraft.class_310")
```

In a dev workspace this returns the Yarn name; in production it returns the
input unchanged. The same three calls exist for methods and fields:

```java
mapMethodName(namespace, ownerDotted, name, descriptor)
mapFieldName (namespace, ownerDotted, name, descriptor)
```

Two things are easy to get wrong here:

1. `owner` must be the **dotted intermediary** class name, and `descriptor` must
   also be in the *source* namespace. The call maps the name only.
2. `GetMethodID` needs the descriptor in the **runtime** namespace, so every
   `L...;` inside it has to be mapped separately. `Mappings::mapDescriptor` does
   this token by token.

## Authoring the table

Writing `class_310` / `method_1551` by hand is unmaintainable. `tools/mappings.def.mjs`
is written in readable Yarn names and `tools/genmappings.mjs` converts it using
the Yarn v2 tiny file, emitting `payload/src/bridge/Mappings.generated.{hpp,cpp}`.

```
node tools/genmappings.mjs
classes 45  methods 85/86  fields 57/57
```

The generator reports every miss with the reason, which is how the real
declaring classes were found — several members are not declared where you would
expect:

| Wanted on | Actually declared on |
| --- | --- |
| `PlayerEntity.getMainHandStack` | `LivingEntity` |
| `PlayerInventory.getStack` | `Inventory` (interface) |
| `World.getBlockState` | `BlockView` (interface) |
| `ClientWorld.getPlayers` | `EntityView` (interface) |
| `World.getTime` | `WorldAccess` (interface) |
| `ClientPlayNetworkHandler.sendPacket` | `ClientCommonNetworkHandler` |
| `AbstractClientPlayerEntity.getState` | `ClientPlayerLikeEntity` (interface) |

JNI resolves inherited and interface members through the subclass, so a method
id taken from the declaring class is still valid on the concrete object.

## Version notes for 1.21.11

- `Entity.getBoundingBox()` no longer exists as a no-arg method; read the
  `boundingBox` field, or rebuild the box from `getWidth`/`getHeight` around the
  interpolated position (which is what the renderer does anyway).
- `Window.getScaleFactor()` returns `int`, not `double`.
- `Entity.horizontalSpeed` / `prevHorizontalSpeed` are gone. View bob now reads
  `ClientPlayerLikeState.getReverseLerpedDistanceMoved` and `lerpMovement` —
  see [[mc-1-21-11-projection]].

Related: [[finding-minecraft-from-a-native-thread]], [[reading-minecraft-ground-truth]]
