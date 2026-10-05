package cobble.v1_21;

import static cobble.boot.Hook.of;

import cobble.boot.Hook;
import cobble.boot.Hook.At;
import java.util.List;
import org.objectweb.asm.Opcodes;

/**
 * Where 1.21.11's hooks go. These are patched into the game as it runs, so
 * they are written in Fabric's intermediary names, which the build does not
 * rename; the Yarn name of each is beside it.
 */
final class HookTable {
    private static final String MINECRAFT = "net/minecraft/class_310";           // MinecraftClient
    private static final String GAME_RENDERER = "net/minecraft/class_757";       // GameRenderer
    private static final String WINDOW = "net/minecraft/class_1041";             // Window
    private static final String PLAYER = "net/minecraft/class_746";              // ClientPlayerEntity
    private static final String WORLD_RENDERER = "net/minecraft/class_761";      // WorldRenderer
    private static final String CAMERA = "net/minecraft/class_4184";             // Camera
    private static final String TICK_COUNTER = "net/minecraft/class_9779$class_9781";  // RenderTickCounter.Dynamic
    private static final String INTERACTION = "net/minecraft/class_636";         // ClientPlayerInteractionManager
    private static final String ENTITY = "net/minecraft/class_1297";             // Entity

    /**
     * The tick hook sits after the crosshair ray and before input handling, as
     * on 1.8.9, so the clicks modules queue are drained by the game's own
     * input pass in the same tick. The frame hook runs right before the
     * buffer swap, once the game has blitted its frame to the window.
     */
    static final List<Hook> ALL = List.of(
            of(MINECRAFT, "method_1574", "()V",                                   // tick
                    At.after(Opcodes.INVOKEVIRTUAL, GAME_RENDERER, "method_3190", "(F)V", 0), "tick"),  // updateCrosshairTarget
            of(MINECRAFT, "method_1574", "()V", At.RETURN, "tickEnd"),
            of(WINDOW, "method_15998", "(Lnet/minecraft/class_10219;)V", At.HEAD, "frame"),          // swapBuffers
            of(PLAYER, "method_3136", "()V", At.HEAD, "preMotion").withSelf(),                        // sendMovementPackets
            of(PLAYER, "method_3136", "()V", At.RETURN, "postMotion").withSelf(),
            of(INTERACTION, "method_2918", "(Lnet/minecraft/class_1657;Lnet/minecraft/class_1297;)V",   // attackEntity
                    At.HEAD, "attackEntity").withArgs().cancelling(),
            of(ENTITY, "method_5871", "()F", At.RETURN, "targetingMargin").withSelf().withResult(),        // getTargetingMargin
            of(CAMERA, "method_19318", "(F)F", At.RETURN, "cameraClip").withArgs().withResult(),           // clipToSpace
            of(TICK_COUNTER, "method_60639", "(J)I",                                                    // beginRenderTick
                    At.redirect(Opcodes.INVOKEINTERFACE, "it/unimi/dsi/fastutil/floats/FloatUnaryOperator", "apply",
                            "(F)F", 0), "tickLength"),
            of(WORLD_RENDERER, "method_22710",                                                        // render
                    "(Lnet/minecraft/class_9922;Lnet/minecraft/class_9779;ZLnet/minecraft/class_4184;"
                            + "Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;"
                            + "Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V",
                    At.HEAD, "worldRendered").withArgs());

    private HookTable() {}
}
