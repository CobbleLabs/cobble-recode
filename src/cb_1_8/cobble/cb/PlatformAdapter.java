package cobble.cb;

import cobble.boot.Hook;
import cobble.boot.Hook.At;
import cobble.core.Assets;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * CheatBreaker's 1.8.9: Lunar's adapter, run under CheatBreaker's names. The
 * build rewrites the legacy code into those names and writes {@link HookTable}
 * out in them; see tools/Remap.java for the file's columns.
 */
public final class PlatformAdapter extends cobble.v1_8.PlatformAdapter {
    private final List<Hook> hooks = readHooks();

    @Override
    public List<Hook> hooks() {
        return hooks;
    }

    private static List<Hook> readHooks() {
        List<Hook> hooks = new ArrayList<>();
        for (String line : new String(Assets.read("cobble/cb/hooks.tsv"), StandardCharsets.UTF_8).split("\n")) {
            String[] column = line.split("\t");
            At.Kind kind = At.Kind.valueOf(column[3]);
            At at = switch (kind) {
                case HEAD -> At.HEAD;
                case RETURN -> At.RETURN;
                default -> new At(kind, Integer.parseInt(column[4]), column[5], column[6], column[7],
                        Integer.parseInt(column[8]));
            };
            String flags = column[10];
            hooks.add(new Hook(column[0], column[1], column[2], at, column[9], flags.contains("s"),
                    flags.contains("a"), flags.contains("c"), flags.contains("r")));
        }
        return hooks;
    }
}
