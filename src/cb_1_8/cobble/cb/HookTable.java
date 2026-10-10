package cobble.cb;

import cobble.boot.Hook;
import cobble.boot.Hook.At;
import java.util.List;
import org.objectweb.asm.Opcodes;

/**
 * Where CheatBreaker 1.8.9's hooks go, still under MCP names: Lunar's, with
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

    public static final List<Hook> ALL = cobble.hook.HookTable.ALL.stream()
            .filter(hook -> !hook.handler().equals(PERSPECTIVE_KEY))
            .map(HookTable::unwrapLunar)
            .toList();

    private HookTable() {}

    /* Lunar draws the HUD through a wrapper around GuiIngame.renderGameOverlay; CheatBreaker calls it directly. */
    private static Hook unwrapLunar(Hook hook) {
        At at = hook.at();
        if (at.name() == null || !at.name().contains(LUNAR)) {
            return hook;
        }
        At vanilla = new At(at.kind(), Opcodes.INVOKEVIRTUAL, GUI_INGAME, "renderGameOverlay", "(F)V", 0);
        return new Hook(hook.owner(), hook.method(), hook.descriptor(), vanilla, hook.handler(), hook.self(),
                hook.args(), hook.cancellable(), hook.result());
    }
}
