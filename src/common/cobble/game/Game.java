package cobble.game;

import cobble.core.Vec3;
import java.util.List;

/**
 * Everything the version-neutral client reads from and does to the game.
 * Reads are gathered into a {@link FrameState}; writes go straight through.
 * Every method runs on the client thread, from a hook, with the game's own
 * state consistent. Entity handles are the game's own objects, passed back
 * as they were handed out.
 */
public interface Game {
    static Game get() {
        return Holder.game;
    }

    static void install(Game game) {
        Holder.game = game;
    }

    /**
     * Reads the game as of {@code partialTicks} through the current tick: the
     * frame's own fraction when drawing, 1 when acting from the tick, where the
     * tick position is the one the server holds.
     */
    FrameState capture(float partialTicks);

    float partialTicks();

    boolean paused();

    boolean inWorld();

    /** Runs {@code task} on the client thread and waits for it, for code arriving on another one. */
    void runOnClientThread(Runnable task) throws Exception;

    boolean multiplayerScreenOpen();

    void setRotation(float yaw, float pitch, boolean alsoPrevious);

    /** Turns the view the way mouse movement does, by counts already scaled for sensitivity. */
    void turn(float yawCounts, float pitchCounts);

    void attackEntity(Object target);

    /** Parks vanilla left click, so a held button cannot double up on a module-driven swing. */
    void setAttackCooldown(int ticks);

    /*
     * Both cooldowns count down inside the tick and the game only acts on the
     * tick they reach zero. Anything shortening one has to read it and lower
     * it, never write it every tick, or it never reaches zero at all.
     */
    int itemUseCooldown();

    void setItemUseCooldown(int ticks);

    /** The delay the controller waits between finishing one block and starting the next. */
    int blockBreakingCooldown();

    void setBlockBreakingCooldown(int ticks);

    boolean breakingBlock();

    void swingHand();

    void setSprinting(boolean sprinting);

    void jump();

    boolean placeBlock(int x, int y, int z, Face face, Vec3 hit, boolean swing);

    /**
     * Makes a queued click act on a face the module chose. The tick hook runs
     * after the game's own crosshair pick and before the click is handled, so
     * the value written here is the one the click reads.
     */
    void setCrosshairTarget(int x, int y, int z, Face face, Vec3 hit);

    /** Whether placing against this position would replace it, as the game's block item decides. */
    boolean isBlockReplaceable(int x, int y, int z);

    boolean isBlockAir(int x, int y, int z);

    boolean blockIs(int x, int y, int z, BlockKind kind);

    String blockId(int x, int y, int z);

    /** Vanilla's speed for the stack in a slot against a block, enchantments aside; a fist is 1. */
    float toolSpeed(int slot, int x, int y, int z);

    /** Vanilla's own "is there room" test; any entity that blocks placement blocks it here. */
    boolean boxEmpty(Vec3 minimum, Vec3 maximum);

    boolean handEmpty();

    boolean holdingBlock();

    default boolean holdingSword() {
        return holdingItem(ItemKind.SWORD);
    }

    /** Sword or axe: what AutoTool treats as a weapon worth keeping in hand. */
    default boolean holdingWeapon() {
        return holdingItem(ItemKind.SWORD) || holdingItem(ItemKind.AXE);
    }

    boolean holdingItem(ItemKind kind);

    /** Inventory index of the first matching stack in [from, to], or -1. Hotbar is 0..8. */
    int findSlot(ItemKind kind, int from, int to);

    int selectedSlot();

    /** Hotbar selection, sent to the server straight away the way the scroll wheel's is on the next tick. */
    void setSelectedSlot(int slot);

    /**
     * Moves an inventory slot into a hotbar slot through the number-key click
     * the inventory screen sends. The player's container is open whether or
     * not a screen is showing, so this needs no window.
     */
    boolean swapSlot(int slot, int hotbar);

    /**
     * Moves an inventory slot into the off hand through the swap click the
     * inventory screen sends for its off hand key; false where there is no off hand.
     */
    boolean swapToOffhand(int slot);

    /** Charges in the respawn anchor at a position, or -1 when it is no anchor. */
    int anchorCharges(int x, int y, int z);

    CrosshairTarget crosshair();

    boolean isZombie(Object entity);

    boolean isPlayer(Object entity);

    /** Ticks left before the entity can be hurt again; knockback lands only once it is low enough. */
    int hurtResistance(Object entity);

    /** Entity id of whatever the player last hit, or -1. */
    int attackTargetId();

    /** The first swing at a block, or the continuation of one. */
    void attackBlock(int x, int y, int z, Face face, boolean progressOnly);

    /** Holds or releases the vanilla binding, which is what a CPS readout counts. */
    void pressKey(boolean useKey, boolean pressed);

    /**
     * Queues one click, which the game drains in the same tick with its own
     * click handling, so the interaction leaves from exactly where a real
     * click's would.
     */
    void queueClick(boolean useKey);

    /**
     * Posts a button event the way the window does, so the game and everything
     * hooked into its mouse handling, Lunar's CPS counter included, take it
     * for the physical button. What the button physically reads is unchanged.
     */
    void queueMouseButton(int button, boolean pressed);

    /**
     * While on, keystroke displays read the button as the events
     * {@link #queueMouseButton} posted, not as it is physically held. A clicker
     * turns it on while it clicks for a held button, which would otherwise
     * show as one long press.
     */
    void showQueuedMouseButton(int button, boolean shown);

    /** No screen up and the cursor captured by the game, which is when a click lands in the world. */
    boolean inGameFocused();

    /**
     * The container slot under the cursor whose stack a shift click would
     * move, or -1: the screen has to be the player's open container and
     * nothing may be held on the cursor.
     */
    int quickMoveSlot();

    void quickMove(int containerSlot);

    void openInventory();

    boolean inventoryOpen();

    void closeScreen();

    void respawn();

    /** Sends a chat line, or a slash command when {@code command} is set. */
    void sendChat(String text, boolean command);

    /** Writes a line into the player's own chat without sending anything to the server. */
    void printChat(String text);

    /** The line above the hotbar, where the server's own action bar messages go. */
    void overlayMessage(String text);

    String entityName(Object entity);

    String entityUuid(Object entity);

    /** Round trip the tab list has for this player, or -1. */
    int entityLatency(Object player);

    void setVelocity(Object entity, Vec3 velocity);

    /** Plays a vanilla sound at the player, by sound event name such as "entity.experience_orb.pickup". */
    void playSound(String id, float volume, float pitch);

    /** Address of the server, or an empty string in single player. */
    String serverAddress();

    String gameDirectory();

    /*
     * Vanilla bindings by name: forward, back, left, right, jump, sneak,
     * sprint, attack and use. Writing one moves the player the way the
     * keyboard does, through the same state the game reads; the next physical
     * event on that key overwrites it, exactly as it would a real press.
     */
    boolean setBinding(String name, boolean pressed);

    /**
     * Whether the game considers the binding held, which a module may have
     * changed; {@link #bindingPhysicallyHeld} is what the player is doing.
     */
    boolean bindingPressed(String name);

    /** Whether the key or button the binding is bound to is down right now. */
    boolean bindingPhysicallyHeld(String name);

    record StackView(boolean present, boolean block, String id, int count, int damage, int maxDamage) {
        public static final StackView EMPTY = new StackView(false, false, "", 0, 0, 0);
    }

    /**
     * One inventory slot, as a script sees it; 0..8 is the hotbar, 36..39
     * armour, 40 the off hand where there is one.
     */
    StackView stack(int slot);

    record EffectView(String id, int amplifier, int duration) {}

    List<EffectView> statusEffects(Object entity);

    enum Slot { HEAD, CHEST, LEGS, FEET, MAIN_HAND, OFF_HAND }

    record EnchantView(String id, int level) {}

    /** Display name rather than registry path: a renamed sword is called what its owner called it. */
    record EquipmentView(boolean present, String id, String name, int count, int damage, int maxDamage,
                         List<EnchantView> enchantments) {
        public static final EquipmentView EMPTY = new EquipmentView(false, "", "", 0, 0, 0, List.of());
    }

    EquipmentView equipment(Object entity, Slot slot);

    /** GL names of textures the game already uploaded. Borrowed, never owned. */
    record SkinView(int texture, boolean slim) {
        public static final SkinView NONE = new SkinView(0, false);
    }

    SkinView playerSkin(Object player);

    record SpriteView(boolean valid, int texture, float u0, float v0, float u1, float v1) {
        public static final SpriteView NONE = new SpriteView(false, 0, 0, 0, 0, 0);
    }

    /** The icon the item's model uses, or no sprite for an item without a flat one. */
    SpriteView itemSprite(String itemId);

    /** An entity that is not a living one, as one call saw it. */
    record EntityRef(Object handle, int id, Vec3 pos, Vec3 velocity) {}

    /** The ender pearls in flight. */
    List<EntityRef> enderPearls();

    /** The end crystals in the world; none on versions without them. */
    List<EntityRef> endCrystals();

    final class Holder {
        private static Game game;

        private Holder() {}
    }
}
