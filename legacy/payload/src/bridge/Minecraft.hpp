#pragma once

#include <jni.h>

#include <string>
#include <unordered_map>
#include <vector>

#include "core/Math.hpp"

namespace cold::game {

/* One entity as seen by a single frame. `handle` is a JNI local reference that
 * only stays valid inside the frame's LocalFrame, so nothing may cache it. */
struct EntityView {
    jobject handle = nullptr;
    int id = 0;
    bool living = false;
    bool isPlayer = false;
    bool invisible = false;
    bool crystal = false;
    bool sneaking = false;
    bool onGround = false;
    /* Shield up. A blocked hit never reaches damage(), so anything that spends
     * a resource on the swing has to ask first. */
    bool blocking = false;

    Vec3 pos;      // interpolated feet position, what the renderer draws at
    Vec3 tickPos;  // authoritative tick position, what the server reasons about
    Vec3 eyePos;
    float width = 0.6f;
    float height = 1.8f;
    float yaw = 0.0f;
    float pitch = 0.0f;
    float bodyYaw = 0.0f;

    float health = 0.0f;
    float maxHealth = 0.0f;
    float absorption = 0.0f;
    int armor = 0;
    int hurtTime = 0;

    double distance = 0.0;  // eye-to-eye, for target sorting

    Vec3 boxMin() const { return {pos.x - width * 0.5, pos.y, pos.z - width * 0.5}; }
    Vec3 boxMax() const { return {pos.x + width * 0.5, pos.y + height, pos.z + width * 0.5}; }
    Vec3 tickBoxMin() const { return {tickPos.x - width * 0.5, tickPos.y, tickPos.z - width * 0.5}; }
    Vec3 tickBoxMax() const {
        return {tickPos.x + width * 0.5, tickPos.y + height, tickPos.z + width * 0.5};
    }
};

struct LocalPlayerView {
    bool valid = false;
    jobject handle = nullptr;

    Vec3 pos;
    Vec3 eyePos;
    Vec3 velocity;
    float yaw = 0.0f;
    float pitch = 0.0f;
    float lastYaw = 0.0f;
    float lastPitch = 0.0f;
    float eyeHeight = 1.62f;

    float health = 0.0f;
    float maxHealth = 20.0f;
    /* Armour points. Anything estimating what a blast will do to you has to
     * read this: the same explosion is a death sentence naked and a scratch in
     * netherite, and measuring your own damage as though you were naked makes
     * every self-damage limit unreachable. */
    int armor = 0;
    bool onGround = false;
    bool sneaking = false;
    bool sprinting = false;
    bool jumping = false;
    /* Counts down from maxHurtTime after a hit lands. A rising edge on this is
     * the only client-side signal that the player just took knockback, which is
     * what every reset - jump, w-tap, clutch - is timed against. */
    int hurtTime = 0;
    int maxHurtTime = 10;
    double fallDistance = 0.0;
    bool usingItem = false;
    /* Ticks the held item has been in use. A bow's power is a function of
     * exactly this, so anything drawing where an arrow lands needs it. */
    int itemUseTicks = 0;
    float attackCooldown = 1.0f;
    bool limitedAttackSpeed = true;
    int age = 0;
};

struct CameraView {
    Vec3 pos;
    float yaw = 0.0f;
    float pitch = 0.0f;
    float quaternion[4] = {0.0f, 0.0f, 0.0f, 1.0f};
    bool thirdPerson = false;
};

struct FrameState {
    bool inWorld = false;
    bool screenOpen = false;
    float tickProgress = 0.0f;
    int fps = 0;
    int ping = -1;

    int windowWidth = 0;   // framebuffer pixels, the overlay's coordinate space
    int windowHeight = 0;
    int logicalWidth = 0;  // window pixels, the space GLFW reports the cursor in
    int logicalHeight = 0;
    int scaledWidth = 0;
    int scaledHeight = 0;
    int scaleFactor = 1;
    double mouseSensitivity = 0.5;
    /* Server-driven tick counter. Its rate against the wall clock is the only
     * honest read on server TPS a client has. */
    long long worldTime = 0;

    LocalPlayerView player;
    CameraView camera;
    Mat4 projection;
    Mat4 view;
    /* False when the frame's matrices could not be read — during a dimension
     * change, for instance. World-space overlays must not fall back to the
     * previous frame's camera, so everything that projects checks this. */
    bool matricesValid = false;

    std::vector<EntityView> entities;
};

/* Ordered to match the lookup tables in Minecraft.cpp. */
enum class ItemKind : int {
    Totem = 0,
    EndCrystal,
    RespawnAnchor,
    Glowstone,
    Mace,
    Obsidian,
    /* Not a singleton: anything that is an AxeItem, which is exactly the set
     * of vanilla items carrying a non-zero disableBlockingForSeconds. */
    Axe,
    /* Also not a singleton. 1.21.11 dropped SwordItem, so the tag is what is
     * left to ask. */
    Sword,
};

enum class BlockKind : int {
    Obsidian = 0,
    Bedrock,
    RespawnAnchor,
};

enum class BlockFace : int {
    Down = 0,
    Up = 1,
    North = 2,
    South = 3,
    West = 4,
    East = 5,
};

/* What Minecraft's own crosshair pick says the player is pointing at. Every
 * crystal macro is built on this rather than on a search of its own: the
 * server reconstructs an interaction from the rotation it was sent, so acting
 * on anything else is acting on something the server will not agree with. */
struct CrosshairTarget {
    enum class Kind : std::uint8_t {
        Miss,
        Block,
        Entity,
    };

    Kind kind = Kind::Miss;
    int x = 0;
    int y = 0;
    int z = 0;
    BlockFace face = BlockFace::Up;
    Vec3 point;
    int entityId = -1;
    bool crystal = false;
    bool slime = false;
    /* Frame-local, like every other handle the modules are given. */
    jobject entity = nullptr;
};

/* Everything the modules are allowed to do to the game. Reads happen once per
 * frame into a FrameState; writes go straight through. */
class Game {
public:
    static Game& instance();

    bool initialise(JNIEnv* env);
    bool ready() const { return ready_; }

    /* Fills `state` from the live client. Must run inside a LocalFrame owned by
     * the caller, on the render thread. */
    bool capture(JNIEnv* env, FrameState& state);

    /* Whether an interaction packet may be sent right now.
     *
     * Vanilla sends its interactions from handleInputEvents and the movement
     * packet from the player tick straight after, so on the wire an interaction
     * always sits just in front of a movement packet and never just behind one.
     * The client acts from the frame hook instead, which is close enough at any
     * playable framerate - but below about thirty frames a second the frame the
     * modules act on can be the one the tick just happened in, and an
     * interaction sent there lands in the window immediately after the movement
     * packet. That is precisely what Grim's Post check is watching for, and it
     * is the only thing that flagged in a full run against a real GrimAC
     * server.
     *
     * So the client tells the bridge, once a frame, whether this frame is far
     * enough past the last tick, and everything that puts an interaction on the
     * wire asks first. A dropped hit at fifteen frames a second is a better
     * outcome than a flagged one. Anything routed through the game's own
     * binding - queueClick - is exempt: the game sends those itself, at its own
     * moment, in the vanilla order. */
    void setInteractionsAllowed(bool allowed) { interactionsAllowed_ = allowed; }
    bool interactionsAllowed() const { return interactionsAllowed_; }

    void setRotation(JNIEnv* env, jobject player, float yaw, float pitch,
                     bool alsoPrevious = false);
    /* Same, but resolves the player itself. Used from the post-tick probe,
     * which has no FrameState to hand. */
    void setLocalRotation(JNIEnv* env, float yaw, float pitch);
    /* Entity.age of the local player, or -1 when there is none. One field read;
     * the probe uses it to tell a pre-tick call from a post-tick one. */
    int playerAge(JNIEnv* env);
    void attackEntity(JNIEnv* env, jobject player, jobject target);
    /* Parks vanilla left click for `ticks`, exactly as doAttack does, so a held
     * mouse button cannot double up on a module-driven swing. */
    void setAttackCooldown(JNIEnv* env, int ticks);
    void setItemUseCooldown(JNIEnv* env, int ticks);
    /* The 250ms the interaction manager waits between finishing one block and
     * starting the next, and whether it is part way through one right now. */
    void setBlockBreakingCooldown(JNIEnv* env, int ticks);
    bool breakingBlock(JNIEnv* env);
    /* Both cooldowns count down inside the client tick and the game only acts
     * on the tick they reach zero. Anything shortening one therefore has to
     * read it first and lower it, never write it: a value written every tick is
     * a value that is never allowed to reach zero, and the action it gates
     * stops happening at all. */
    int itemUseCooldown(JNIEnv* env);
    int blockBreakingCooldown(JNIEnv* env);
    void swingHand(JNIEnv* env, jobject player, bool offHand);
    void setSprinting(JNIEnv* env, jobject player, bool sprinting);
    void jump(JNIEnv* env, jobject player);
    bool placeBlock(JNIEnv* env, jobject player, int blockX, int blockY, int blockZ, BlockFace face,
                    const Vec3& hitVector, bool swing = false);
    /* Writes `MinecraftClient.crosshairTarget` so a queued press acts on a face
     * the module chose rather than on the one the last rendered frame happened
     * to be pointing at.
     *
     * This is what makes queueClick usable with a silent rotation. The game
     * recomputes the crosshair once per rendered frame, from the camera - which
     * a silent aim never touches - and `doItemUse` runs from the client tick
     * off that cached value, so without this a silent placement lands wherever
     * the player was actually looking. Written on the last frame before the
     * tick there is no render in between, so the value survives to
     * handleInputEvents and is replaced by the game's own on the frame after.
     * See [[silent-rotations-without-bytecode]]. */
    bool setCrosshairTarget(JNIEnv* env, int blockX, int blockY, int blockZ, BlockFace face,
                            const Vec3& hitVector);
    bool isBlockReplaceable(JNIEnv* env, jobject world, int blockX, int blockY, int blockZ);
    /* Strictly air, which is a smaller set than replaceable. An end crystal
     * wants this one: `EndCrystalItem` refuses on anything that is not air,
     * grass included. */
    bool isBlockAir(JNIEnv* env, jobject world, int blockX, int blockY, int blockZ);
    /* True when the selected hotbar slot holds a placeable block. */
    bool holdingBlock(JNIEnv* env, jobject player);
    bool handEmpty(JNIEnv* env, jobject player, bool offHand);
    bool stackIs(JNIEnv* env, jobject stack, ItemKind kind);
    bool holdingItem(JNIEnv* env, jobject player, ItemKind kind, bool offHand = false);
    /* Slot index of the first matching stack in [from, to], or -1. Hotbar is
     * 0..8, the rest of the inventory 9..35. */
    int findSlot(JNIEnv* env, jobject player, ItemKind kind, int from, int to);
    int selectedSlot(JNIEnv* env, jobject player);
    /* Moves an inventory slot into the offhand, or into a hotbar slot, through
     * the same click the inventory screen sends. `slot` is a player-inventory
     * index; `hotbar` is 0..8, or -1 for the offhand.
     *
     * The player screen handler is open whether or not a screen is showing, so
     * this needs no window and moves no cursor. */
    bool swapSlot(JNIEnv* env, jobject player, int slot, int hotbar);
    void setSelectedSlot(JNIEnv* env, jobject player, int slot);
    bool blockIs(JNIEnv* env, jobject world, int blockX, int blockY, int blockZ, BlockKind kind);

    /* ---- what the crosshair macros need ---- */
    CrosshairTarget crosshair(JNIEnv* env);
    /* Charge level of a respawn anchor, or -1 when the block is not one. */
    int anchorCharges(JNIEnv* env, jobject world, int blockX, int blockY, int blockZ);
    /* Vanilla's own "is there room" test, entities included: an item lying in
     * the space stops an end crystal exactly as a mob does. */
    bool boxEmpty(JNIEnv* env, jobject world, const Vec3& minimum, const Vec3& maximum);
    bool holdingSword(JNIEnv* env, jobject player);
    /* Shield in main hand or offhand. Argon's triggerbot blocks with it
     * between hits and releases it for the hit itself. */
    bool holdingShield(JNIEnv* env, jobject player, bool offHand = false);
    /* Stray's anti-triggerbot is dodged by hitting its zombie stand-ins, so
     * the port needs the exact instanceof Argon checks. */
    bool isZombie(JNIEnv* env, jobject entity);
    /* Entity id of whatever the player's attack target currently is
     * (LivingEntity.getAttacking), or -1. Argon's Same Player gate. */
    int attackTargetId(JNIEnv* env, jobject player);
    /* Sword or axe. 1.21.11 dropped ToolItem and the material behind it, so
     * "a weapon that still hits through weakness" is as close as the tags get. */
    bool holdingWeapon(JNIEnv* env, jobject player);
    /* Amplifier of weakness or strength on the player, or -1 when it is off. */
    int effectAmplifier(JNIEnv* env, jobject player, bool weakness);
    /* The first swing at a block, or the continuation of one. */
    void attackBlock(JNIEnv* env, int blockX, int blockY, int blockZ, BlockFace face,
                     bool progressOnly);
    void breakingParticle(JNIEnv* env, jobject world, int blockX, int blockY, int blockZ,
                          BlockFace face);
    /* Holds or releases the vanilla binding, which is what a CPS readout
     * counts. The matching cooldown has to be parked as well or the game acts
     * on the press too. */
    void pressKey(JNIEnv* env, bool useKey, bool pressed);
    /* Queues one click on a binding, which the game acts on during its own
     * tick. Everything the macros do goes through this: an interaction the
     * payload sends itself arrives after that tick's movement packet, and that
     * is exactly what Grim's Post check is looking for. */
    void queueClick(JNIEnv* env, bool useKey);
    void openInventory(JNIEnv* env, jobject player);
    bool inventoryOpen(JNIEnv* env);
    bool hasLastAttacker(JNIEnv* env, jobject entity);
    bool isAttackingPlayer(JNIEnv* env, jobject player);

    /* Frame-local reference to ClientWorld; valid inside the caller's frame. */
    jobject world(JNIEnv* env);
    /* Sends a chat line, or a slash command when `command` is true. Used by the
     * scripted test harness to set a world up without touching the keyboard. */
    bool sendChat(JNIEnv* env, const std::string& text, bool command);
    /* Writes a line into the player's own chat without sending anything to the
     * server, which is how a script talks back. A negative colour leaves the
     * line the colour chat already is. */
    bool printChat(JNIEnv* env, const std::string& text, int colour = -1);
    /* The line above the hotbar, where Minecraft puts its own one-shot
     * messages. */
    bool overlayMessage(JNIEnv* env, const std::string& text);
    /* Display name of an entity. `Nameable.getStringifiedName` is exactly this
     * and returns a plain String, so no Text handling is involved. */
    std::string entityName(JNIEnv* env, jobject entity);

    /* ---- what the script API asks for and the modules never needed ---- */

    /* One stack, read as a script sees it. `id` is the registry path, so
     * "diamond_sword" rather than an object identity. */
    struct StackView {
        bool present = false;
        /* Placeable, i.e. a BlockItem. Asked per slot rather than per hand, so
         * anything scanning the hotbar for something to build with does not
         * have to guess from the registry path. */
        bool block = false;
        std::string id;
        int count = 0;
        int damage = 0;
        int maxDamage = 0;
    };
    StackView stack(JNIEnv* env, jobject player, int slot);
    std::string blockId(JNIEnv* env, jobject world, int blockX, int blockY, int blockZ);

    struct EffectView {
        std::string id;
        int amplifier = 0;
        int duration = 0;
    };
    std::vector<EffectView> statusEffects(JNIEnv* env, jobject entity);

    /* ---- what the player radar reads off somebody else ----
     *
     * All of it is information the client already holds: equipment arrives in
     * EntityEquipmentUpdate, the skin has already been downloaded and uploaded
     * for the world render, and the item sprites are the ones the game stitched
     * at load. Nothing here asks the server for anything it did not send. */

    struct EnchantView {
        std::string id;  // registry path, so "fire_aspect"
        int level = 0;
    };

    struct EquipmentView {
        bool present = false;
        std::string id;
        /* Display name rather than the registry path: a renamed mace is called
         * what its owner called it, and a vanilla item is named in the reader's
         * own language. */
        std::string name;
        int count = 1;
        int damage = 0;
        int maxDamage = 0;
        std::vector<EnchantView> enchantments;
    };

    /* Slots in the order the radar shows them. */
    enum class Slot : int { Head = 0, Chest, Legs, Feet, MainHand, OffHand, Count };
    EquipmentView equipment(JNIEnv* env, jobject entity, Slot slot);

    struct SkinView {
        /* GL name of the texture the game has already uploaded. Borrowed, never
         * owned: nothing here creates or deletes a texture. */
        unsigned int texture = 0;
        bool slim = false;
    };
    SkinView playerSkin(JNIEnv* env, jobject player);

    /* One stitched sprite out of an atlas, ready to be drawn as a textured
     * quad. `itemId` is a registry path such as "diamond_sword". */
    struct SpriteView {
        bool valid = false;
        unsigned int texture = 0;
        float u0 = 0.0f;
        float v0 = 0.0f;
        float u1 = 0.0f;
        float v1 = 0.0f;
    };
    SpriteView itemSprite(JNIEnv* env, const std::string& itemId);
    /* Atlas contents survive for the life of a resource pack, so the lookup is
     * cached. A resource reload invalidates every GL name in it. */
    void resetSpriteCache();

    std::string entityUuid(JNIEnv* env, jobject entity);
    /* Round trip the tab list has for this player, or -1. */
    int entityLatency(JNIEnv* env, jobject player);

    /* Vanilla movement bindings by name: forward, back, left, right, jump,
     * sneak, sprint, attack, use. A script driving these moves the player the
     * way the keyboard does, through the same input the game reads. */
    bool setBinding(JNIEnv* env, const char* name, bool pressed);
    /* Whether the game currently considers that binding held, which is not the
     * same question as whether a physical key is down: the player may have
     * rebound it, and a module may be holding it for them. */
    bool bindingPressed(JNIEnv* env, const char* name);
    /* GLFW code the binding is bound to, or 0. This is the other half of the
     * question: a module that takes a key off the player needs to know whether
     * they are still physically holding it, and the binding state cannot say -
     * the module is the one that cleared it. */
    int bindingKey(JNIEnv* env, const char* name);
    void setVelocity(JNIEnv* env, jobject entity, const Vec3& velocity);
    /* Plays a vanilla sound at the player, by registry path. */
    bool playSound(JNIEnv* env, jobject player, const std::string& id, float volume, float pitch);

    /* Address of the server, or an empty string in single player. */
    std::string serverAddress(JNIEnv* env);
    std::string gameDirectory(JNIEnv* env);
    bool respawn(JNIEnv* env, jobject player);
    void closeScreen(JNIEnv* env);

    /* Hands the cursor to the menu and back through Minecraft's own Mouse, then
     * re-anchors its stored position.
     *
     * Minecraft derives camera movement from `cursor - Mouse.x`, and while the
     * menu owns the cursor those events are swallowed, so `Mouse.x` goes stale.
     * Without re-anchoring, the first event after closing carries the whole
     * distance the pointer travelled across the menu and spins the player. */
    void setCursorReleased(JNIEnv* env, bool released);
    void anchorCursor(JNIEnv* env, double x, double y);

private:
    Game() = default;

    bool captureWindow(JNIEnv* env, jobject client, FrameState& state);
    bool captureMatrices(JNIEnv* env, jobject client, FrameState& state);
    void capturePlayer(JNIEnv* env, jobject client, jobject player, FrameState& state);
    void captureEntities(JNIEnv* env, jobject world, FrameState& state);
    int capturePing(JNIEnv* env, jobject client, jobject player);

    bool ready_ = false;
    bool interactionsAllowed_ = true;
    jobject clientInstance_ = nullptr;

    jclass iterableClass_ = nullptr;
    jmethodID iterableIterator_ = nullptr;
    jclass iteratorClass_ = nullptr;
    jmethodID iteratorHasNext_ = nullptr;
    jmethodID iteratorNext_ = nullptr;

    jclass numberClass_ = nullptr;
    jmethodID numberDoubleValue_ = nullptr;
    jclass booleanClass_ = nullptr;
    jmethodID booleanValue_ = nullptr;

    /* GL name of a texture the game owns. 1.8.9's ITextureObject publishes it
     * as getGlTextureId(), so this is a call, not the reflection hunt the
     * 1.21 GpuTexture abstraction forced. */
    unsigned int textureName(JNIEnv* env, jobject abstractTexture);

    jobject itemAtlas(JNIEnv* env, bool blocks);
    std::unordered_map<std::string, SpriteView> spriteCache_;
    /* The uv rectangle the atlas hands back for a name it does not have.
     * Comparing against it is the only way to tell a real sprite from the
     * missing one. */
    SpriteView missingItemSprite_;
    bool missingResolved_ = false;
};

}  // namespace cold::game
