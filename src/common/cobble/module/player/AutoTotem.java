package cobble.module.player;

import cobble.boot.GameVersion;
import cobble.core.Rng;
import cobble.core.Timer;
import cobble.game.FrameState;
import cobble.game.Game;
import cobble.module.Category;
import cobble.module.Context;
import cobble.module.Module;
import cobble.module.ModuleManager;
import cobble.module.Setting;
import cobble.module.Subcategory;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps a totem in the off hand. The swap is the click the
 * inventory screen sends for its off hand key, made with the screen open by
 * default, as a player would, and on a humanlike delay between each step.
 */
public final class AutoTotem extends Module {
    private static final int OFFHAND = 40;
    private static final int LAST_INVENTORY_SLOT = 35;
    private static final String TOTEM = "totem_of_undying";

    private final Setting openInventory = addBoolean("Open inventory", "Open the inventory to equip a totem", true);
    private final Setting closeInventory = addBoolean("Close inventory", "Close the inventory again after equipping",
            true).visibleWhen(() -> this.openInventory.bool());
    private final Setting inventoryOnly = addBoolean("Inventory only", "Only equip while the inventory is open", false);
    private final Setting randomSlot = addBoolean("Random slot", "Take a random totem rather than the first", true);
    private final Setting delayMin = addNumber("Delay min", "Shortest wait between steps, in ms", 100, 0, 500, 0);
    private final Setting delayMax = addNumber("Delay max", "Longest wait between steps, in ms", 200, 0, 500, 0);

    private final Rng rng = new Rng();
    private final Timer timer = new Timer();
    private long delay;
    private boolean opened;
    private int totems;

    public AutoTotem() {
        super("AutoTotem", "Keeps a totem in your off hand", Category.PLAYER, Subcategory.BASIC);
    }

    @Override
    public boolean supported() {
        return GameVersion.current().atLeast(GameVersion.V1_21_11);
    }

    @Override
    public String suffix() {
        return String.valueOf(totems);
    }

    @Override
    public boolean ownsInventory() {
        return opened;
    }

    @Override
    public void onEnable(Context context) {
        opened = false;
        rearm();
    }

    @Override
    public void onDisable(Context context) {
        close();
    }

    private void rearm() {
        timer.reset();
        int low = (int) Math.min(delayMin.number(), delayMax.number());
        int high = (int) Math.max(delayMin.number(), delayMax.number());
        delay = high <= low ? low : rng.range(low, high);
    }

    private void close() {
        Game game = Game.get();
        if (opened && closeInventory.bool() && game.inventoryOpen()) {
            game.closeScreen();
        }
        opened = false;
    }

    @Override
    public void onTick(Context context) {
        FrameState state = context.state;
        if (!state.inWorld || !state.player.valid || state.player.health <= 0.0f) {
            opened = false;
            return;
        }
        Game game = Game.get();
        List<Integer> slots = totemSlots(game);
        totems = slots.size() + (isTotem(game, OFFHAND) ? 1 : 0);
        if (!timer.elapsed(delay) || !opened && ModuleManager.inventoryBusy()) {
            return;
        }
        if (isTotem(game, OFFHAND) || slots.isEmpty()) {
            if (opened) {
                close();
                rearm();
            }
            return;
        }
        boolean inventory = game.inventoryOpen();
        /* Another screen, a chest say, has its own container: the off hand key would click that. */
        if (state.screenOpen && !inventory || inventoryOnly.bool() && !inventory) {
            return;
        }
        if (openInventory.bool() && !inventory) {
            game.openInventory();
            opened = true;
            rearm();
            return;
        }
        int slot = randomSlot.bool() ? slots.get(rng.range(0, slots.size() - 1)) : slots.get(0);
        game.swapToOffhand(slot);
        rearm();
    }

    private static boolean isTotem(Game game, int slot) {
        return TOTEM.equals(game.stack(slot).id());
    }

    private static List<Integer> totemSlots(Game game) {
        List<Integer> slots = new ArrayList<>();
        for (int slot = 0; slot <= LAST_INVENTORY_SLOT; ++slot) {
            if (isTotem(game, slot)) {
                slots.add(slot);
            }
        }
        return slots;
    }
}
