package cobble.module.player;

import cobble.core.Time;
import cobble.module.Category;
import cobble.module.Context;
import cobble.module.LegacyModule;
import cobble.module.Setting;
import cobble.module.Subcategory;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.ItemPotion;
import net.minecraft.item.ItemStack;

/**
 * One press throws the debuff splash potions on your hotbar, then goes back to
 * the slot you were on. Each throw is a press of the use key on that slot.
 */
public final class ThrowDebuff extends LegacyModule {
    private static final int ALL = 0;
    private static final int ONE_OF_EACH = 1;
    private static final List<String> DEBUFFS = List.of("Harming", "Weakness", "Poison", "Slowness");

    private final Setting mode = addChoice("Mode",
            "All throws every debuff; One of each throws one per kind; First throws only the first",
            List.of("All", "One of each", "First"), ONE_OF_EACH);
    private final List<Setting> kinds = new ArrayList<>();
    private final Setting delayMin = addNumber("Delay min", "Least milliseconds between throws", 70, 0, 200, 0);
    private final Setting delayMax = addNumber("Delay max", "Most milliseconds between throws", 120, 0, 200, 0);
    private final Setting scroll = addBoolean("Scroll", "Walk the hotbar one slot at a time", false);
    private final Setting scrollDelay = addNumber("Scroll delay", "Milliseconds per slot scrolled", 100, 0, 200, 0)
            .visibleWhen(() -> this.scroll.bool());

    private final SplittableRandom random = new SplittableRandom();
    private final Deque<Integer> queue = new ArrayDeque<>();
    private int savedSlot;
    private boolean throwing;
    private long lastThrow;
    private long nextDelay;
    private long lastScroll;

    public ThrowDebuff() {
        super("ThrowDebuff", "Throws the debuff potions on your hotbar", Category.PLAYER, Subcategory.MEDIUM);
        for (String debuff : DEBUFFS) {
            kinds.add(addBoolean(debuff, "Throw " + debuff.toLowerCase(Locale.ROOT) + " potions", true));
        }
    }

    @Override
    public void onEnable(Context context) {
        queue.clear();
        throwing = false;
        if (mc.thePlayer == null || !collect(mc.thePlayer)) {
            setEnabled(false, context);
            return;
        }
        savedSlot = mc.thePlayer.inventory.currentItem;
        lastThrow = 0;
        nextDelay = 0;
    }

    @Override
    public void onDisable(Context context) {
        if (throwing) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), false);
        }
        throwing = false;
        queue.clear();
    }

    @Override
    public void onTick(Context context) {
        KeyBinding use = mc.gameSettings.keyBindUseItem;
        if (throwing) {
            KeyBinding.setKeyBindState(use.getKeyCode(), false);
            throwing = false;
            return;
        }
        if (queue.isEmpty()) {
            if (select(savedSlot)) {
                setEnabled(false, context);
            }
            return;
        }
        long now = Time.nowMillis();
        if (now - lastThrow < nextDelay || !select(queue.peek())) {
            return;
        }
        KeyBinding.setKeyBindState(use.getKeyCode(), true);
        KeyBinding.onTick(use.getKeyCode());
        throwing = true;
        lastThrow = now;
        int low = Math.min(delayMin.intValue(), delayMax.intValue());
        nextDelay = low + random.nextInt(Math.max(delayMin.intValue(), delayMax.intValue()) - low + 1);
        queue.poll();
    }

    /* Hotbar slots in order, each matched by the debuff its name carries. */
    private boolean collect(EntityPlayerSP player) {
        List<String> queued = new ArrayList<>();
        for (int slot = 0; slot < 9; ++slot) {
            ItemStack stack = player.inventory.mainInventory[slot];
            if (stack == null || !(stack.getItem() instanceof ItemPotion) || !ItemPotion.isSplash(stack.getMetadata())) {
                continue;
            }
            String name = stack.getDisplayName().toLowerCase(Locale.ROOT);
            for (int kind = 0; kind < DEBUFFS.size(); ++kind) {
                String debuff = DEBUFFS.get(kind);
                if (!kinds.get(kind).bool() || mode.choice() == ONE_OF_EACH && queued.contains(debuff)
                        || !name.contains(debuff.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                queue.add(slot);
                queued.add(debuff);
                if (mode.choice() != ALL && mode.choice() != ONE_OF_EACH) {
                    return true;
                }
                break;
            }
        }
        return !queue.isEmpty();
    }

    /* True once the slot is held; with Scroll, one slot nearer per scroll delay until it is. */
    private boolean select(int slot) {
        EntityPlayerSP player = mc.thePlayer;
        if (!scroll.bool()) {
            player.inventory.currentItem = slot;
            return true;
        }
        long now = Time.nowMillis();
        if (now - lastScroll < scrollDelay.intValue()) {
            return false;
        }
        int current = player.inventory.currentItem;
        if (current == slot) {
            lastScroll = now;
            return true;
        }
        player.inventory.currentItem = current + Integer.signum(slot - current);
        return false;
    }
}
