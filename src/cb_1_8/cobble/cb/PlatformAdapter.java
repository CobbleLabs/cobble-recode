package cobble.cb;

import cobble.boot.Hook;
import cobble.core.Assets;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * CheatBreaker's 1.8.9: Lunar's adapter, run under CheatBreaker's names. The
 * build rewrites the legacy code into those names and writes {@link HookTable}
 * out in them; see tools/Remap.java.
 */
public final class PlatformAdapter extends cobble.v1_8.PlatformAdapter {
    private final List<Hook> hooks =
            Hook.parse(new String(Assets.read("cobble/cb/hooks.tsv"), StandardCharsets.UTF_8));

    @Override
    public List<Hook> hooks() {
        return hooks;
    }
}
