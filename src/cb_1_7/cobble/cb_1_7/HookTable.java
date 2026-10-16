package cobble.cb_1_7;

import cobble.boot.Hook;
import cobble.boot.Hook.At;
import java.util.List;
import java.util.Map;
import org.objectweb.asm.Opcodes;

/**
 * Where CheatBreaker 1.7.10's hooks go, still under MCP names: Lunar's, with
 * the anchors on Lunar's own mixin methods moved to the vanilla calls they
 * wrap. The build puts the table under CheatBreaker's names and fails on any
 * hook that would not apply; see tools/Remap.java.
 */
public final class HookTable {
    private static final String GUI_INGAME = "net/minecraft/client/gui/GuiIngame";
    private static final String LUNAR = "$lunar$";
    /*
     * CheatBreaker moved the perspective key out of runTick into a method of
     * its own that vanilla has no name for, so F5 changes the view even while
     * FreeLook is on.
     */
    private static final String PERSPECTIVE_KEY = "setThirdPersonView";
    /*
     * Handlers whose method CheatBreaker replaced with one of its own, by the
     * descriptor of that method, which vanilla has no name for: its
     * setupCameraTransform takes a flag more, and the player renderer asks its
     * own cape getter, which falls back to vanilla's cape only when no
     * CheatBreaker cape is worn.
     */
    private static final Map<String, String> OWN_METHODS = Map.of(
            "nauseaPotion", "(FIZ)V",
            "capeTexture", "()Lnet/minecraft/util/ResourceLocation;");

    public static final List<Hook> ALL = cobble.v1_7.HookTable.ALL.stream()
            .filter(hook -> !hook.handler().equals(PERSPECTIVE_KEY))
            .map(HookTable::ownMethod)
            .map(HookTable::unwrapLunar)
            .toList();

    private HookTable() {}

    private static Hook ownMethod(Hook hook) {
        String descriptor = OWN_METHODS.get(hook.handler());
        if (descriptor == null) {
            return hook;
        }
        return new Hook(hook.owner(), "*", descriptor, hook.at(), hook.handler(), hook.self(), hook.args(),
                hook.cancellable(), hook.result());
    }

    /* Lunar draws the HUD through a redirect of GuiIngame.renderGameOverlay; CheatBreaker calls it directly. */
    private static Hook unwrapLunar(Hook hook) {
        At at = hook.at();
        if (at.name() == null || !at.name().contains(LUNAR)) {
            return hook;
        }
        At vanilla = new At(at.kind(), Opcodes.INVOKEVIRTUAL, GUI_INGAME, "renderGameOverlay", "(FZII)V", 0);
        return new Hook(hook.owner(), hook.method(), hook.descriptor(), vanilla, hook.handler(), hook.self(),
                hook.args(), hook.cancellable(), hook.result());
    }
}
