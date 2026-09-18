# Read the jar, do not guess

Every time this project guessed at Minecraft's behaviour from memory of an older
version, the guess was wrong. Loom leaves a fully remapped jar in its cache, and
`javap` reads it in a second.

```bash
JAR=~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged/\
1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2/\
minecraft-merged-1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2.jar

# what does this class actually expose?
javap -p -cp "$JAR" net.minecraft.client.render.GameRenderer

# what does this method actually do?
javap -p -c -cp "$JAR" net.minecraft.client.render.GameRenderer |
    awk '/private void bobView/,/^$/'

# just the calls, for tracing a pipeline
javap -p -c -cp "$JAR" net.minecraft.client.MinecraftClient |
    awk '/public void render/,/^  private/' | grep -E '//'
```

Things this settled that would otherwise have been wrong:

- View bob is multiplied into the **projection** matrix, not the view matrix
  ([[mc-1-21-11-projection]]).
- `ClientPlayerInteractionManager.attackEntity` does **not** swing; `doAttack`
  swings first, then attacks ([[grim-rotation-ordering]]).
- `bobView` reads `ClientPlayerLikeState`, not the `horizontalSpeed` fields that
  older versions used.
- `GameRenderer.getProjectionMatrix(float)` is not the world projection — it
  skips bob entirely.

For the name-level questions (which class declares a member, what is a member's
intermediary name) `tools/mapq.mjs` answers faster than javap:

```bash
node tools/mapq.mjs class  client/MinecraftClient
node tools/mapq.mjs member net/minecraft/entity/Entity getYaw
node tools/mapq.mjs find   sendChatCommand
```

Related: [[fabric-jni-bridge]]
