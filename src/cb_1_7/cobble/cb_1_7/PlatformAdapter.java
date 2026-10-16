package cobble.cb_1_7;

import cobble.boot.Hook;
import cobble.core.Assets;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * CheatBreaker's 1.7.10: Lunar's adapter, run under CheatBreaker's names. The
 * build rewrites the 1.7.10 code into those names and writes {@link HookTable}
 * out in them; see tools/Remap.java.
 */
public final class PlatformAdapter extends cobble.v1_7.PlatformAdapter {
    private final List<Hook> hooks =
            Hook.parse(new String(Assets.read("cobble/cb_1_7/hooks.tsv"), StandardCharsets.UTF_8));

    @Override
    public List<Hook> hooks() {
        return hooks;
    }
}
