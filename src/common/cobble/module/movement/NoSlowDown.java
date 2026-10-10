package cobble.module.movement;

import cobble.module.Category;
import cobble.module.Module;
import cobble.module.Setting;
import cobble.module.Subcategory;
import java.util.List;

/**
 * Cobble's NoSlowDown: using an item no longer cuts your walk to a fifth or
 * drops your sprint. Each kind of use walks at its own speed, from vanilla's
 * 0.2 up to full. The version hooks ask it from where the game applies the
 * slowdown.
 */
public final class NoSlowDown extends Module {
    public enum Use { CONSUME, BLOCK, BOW, OTHER }

    private static final int CONSUMABLE = 0;
    private static final int BLOCKING = 1;
    private static final int BOWS = 2;

    private final Setting mode = addChoice("Mode", "Which uses walk at the speeds below",
            List.of("Consumable", "Block", "Bow", "All"), 3);
    private final Setting consumeSpeed = addNumber("Consume speed", "Walking speed while eating or drinking",
            1.0f, 0.2f, 1.0f, 2);
    private final Setting blockSpeed = addNumber("Block speed",
            "Walking speed while blocking with a sword or a shield", 1.0f, 0.2f, 1.0f, 2);
    private final Setting bowSpeed = addNumber("Bow speed",
            "Walking speed while drawing a bow or a crossbow, or charging a trident", 1.0f, 0.2f, 1.0f, 2);

    public NoSlowDown() {
        super("NoSlowDown", "Walks and sprints while using items", Category.MOVEMENT, Subcategory.MEDIUM);
    }

    @Override
    public String suffix() {
        return mode.choiceName();
    }

    /** Whether the module takes over the slowdown of this use. */
    public boolean covers(Use use) {
        if (!enabled() || use == Use.OTHER) {
            return false;
        }
        return switch (mode.choice()) {
            case CONSUMABLE -> use == Use.CONSUME;
            case BLOCKING -> use == Use.BLOCK;
            case BOWS -> use == Use.BOW;
            default -> true;
        };
    }

    /** The share of the walk a covered use keeps. */
    public float speed(Use use) {
        return switch (use) {
            case CONSUME -> consumeSpeed.number();
            case BLOCK -> blockSpeed.number();
            case BOW -> bowSpeed.number();
            case OTHER -> throw new IllegalArgumentException("no speed for other uses");
        };
    }
}
