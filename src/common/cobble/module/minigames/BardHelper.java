package cobble.module.minigames;

import cobble.core.Timer;
import cobble.game.FrameState;
import cobble.game.Game;
import cobble.game.ItemKind;
import cobble.module.Category;
import cobble.module.Context;
import cobble.module.Module;
import cobble.module.Setting;
import cobble.module.Subcategory;
import cobble.ui.Client;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * HCF bards pass a buff to their team by holding its item, and a stronger one
 * by using it. Steps through the buff items on the hotbar, holding each for
 * the switch delay; Single does it once and switches off, Loop keeps going.
 */
public final class BardHelper extends Module {
    private static final int SINGLE = 0;
    private static final long SHORTEST_WAIT_MS = 10;

    private record Buff(ItemKind item, Setting enabled) {}

    private final Setting mode = addChoice("Mode",
            "Single cycles the buffs once and switches off; Loop keeps cycling while on",
            List.of("Single", "Loop"), SINGLE);
    private final Setting switchBack = addBoolean("Switch back", "Return to the slot you held after each cycle", true);
    private final Setting clickBuff = addBoolean("Click buff", "Use each buff item as it is held", false);
    private final Setting switchDelay = addNumber("Switch delay", "Milliseconds each buff item is held",
            100, 20, 1000, 0);
    private final Setting loopDelay = addNumber("Loop delay", "Milliseconds between cycles", 200, 50, 2000, 0)
            .visibleWhen(() -> this.mode.choice() != SINGLE);
    private final Setting randomization = addNumber("Randomization", "Milliseconds each delay may vary either way",
            15, 0, 50, 0);
    private final List<Buff> buffs = List.of(
            buff("Resistance", "Iron ingot", ItemKind.IRON_INGOT, true),
            buff("Strength", "Blaze powder", ItemKind.BLAZE_POWDER, true),
            buff("Regeneration", "Ghast tear", ItemKind.GHAST_TEAR, true),
            buff("Speed", "Sugar", ItemKind.SUGAR, true),
            buff("Jump boost", "Feather", ItemKind.FEATHER, true),
            buff("Fire resistance", "Magma cream", ItemKind.MAGMA_CREAM, false),
            buff("Debuff", "Spider eye", ItemKind.SPIDER_EYE, false));

    private final SplittableRandom random = new SplittableRandom();
    private final Timer timer = new Timer();
    private long wait;
    private List<Integer> plan = List.of();
    private int index;
    private boolean holding;
    private int previousSlot = -1;

    public BardHelper() {
        super("BardHelper", "Cycles the bard buff items on your hotbar", Category.MINIGAMES, Subcategory.MEDIUM);
    }

    private Buff buff(String name, String item, ItemKind kind, boolean enabled) {
        return new Buff(kind, addBoolean(name, item, enabled));
    }

    @Override
    public String suffix() {
        return mode.choiceName();
    }

    @Override
    public void onEnable(Context context) {
        if (!ready(context.state)) {
            setEnabled(false, context);
            return;
        }
        startCycle(Game.get(), context);
    }

    @Override
    public void onDisable(Context context) {
        if (context.state.player.valid) {
            restore(Game.get());
        }
        previousSlot = -1;
    }

    @Override
    public void onTick(Context context) {
        FrameState state = context.state;
        if (!state.player.valid) {
            setEnabled(false, context);
            return;
        }
        if (!ready(state)) {
            if (mode.choice() == SINGLE) {
                setEnabled(false, context);
            }
            return;
        }
        if (!timer.elapsed(wait)) {
            return;
        }
        Game game = Game.get();
        if (!holding) {
            startCycle(game, context);
        } else if (++index < plan.size()) {
            hold(game);
        } else {
            restore(game);
            endCycle(context);
        }
    }

    private boolean ready(FrameState state) {
        return state.player.valid && !state.screenOpen && !Client.gui().visible();
    }

    private void startCycle(Game game, Context context) {
        plan = plan(game);
        index = 0;
        if (plan.isEmpty()) {
            endCycle(context);
            return;
        }
        previousSlot = game.selectedSlot();
        holding = true;
        hold(game);
    }

    private void endCycle(Context context) {
        holding = false;
        if (mode.choice() == SINGLE) {
            setEnabled(false, context);
        } else {
            schedule(loopDelay);
        }
    }

    /** The hotbar slot of each enabled buff, first stack of each, in hotbar order. */
    private List<Integer> plan(Game game) {
        List<Integer> slots = new ArrayList<>();
        for (Buff buff : buffs) {
            int slot = buff.enabled.bool() ? game.findSlot(buff.item, 0, 8) : -1;
            if (slot >= 0) {
                slots.add(slot);
            }
        }
        slots.sort(null);
        return slots;
    }

    private void hold(Game game) {
        game.setSelectedSlot(plan.get(index));
        if (clickBuff.bool()) {
            game.queueClick(true);
        }
        schedule(switchDelay);
    }

    private void restore(Game game) {
        if (switchBack.bool() && previousSlot >= 0) {
            game.setSelectedSlot(previousSlot);
        }
        previousSlot = -1;
    }

    private void schedule(Setting delay) {
        int spread = randomization.intValue();
        int jitter = spread == 0 ? 0 : random.nextInt(-spread, spread + 1);
        wait = Math.max(SHORTEST_WAIT_MS, delay.intValue() + jitter);
        timer.reset();
    }
}
