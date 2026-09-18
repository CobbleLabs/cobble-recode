#include "modules/player/AutoTool.hpp"

#include <cstdint>
#include <string_view>

#include "bridge/Minecraft.hpp"
#include "hooks/Input.hpp"

namespace cold::modules {
namespace {

enum class Family : std::uint8_t {
    None,
    Pickaxe,
    Axe,
    Shovel,
    Hoe,
    Sword,
    Shears,
};

bool contains(const std::string& value, std::string_view needle) {
    return value.find(needle) != std::string::npos;
}

bool endsWith(const std::string& value, std::string_view suffix) {
    return value.size() >= suffix.size() &&
           value.compare(value.size() - suffix.size(), suffix.size(), suffix) == 0;
}

/* Which tool a block answers to.
 *
 * Ordered so the narrow cases are decided before the broad ones: a spruce
 * fence is wood, an iron door is not, and half the registry has "block" in it
 * somewhere. Anything that falls through is left to the fist, which is the
 * right answer for glass, buttons and torches alike. */
Family familyFor(const std::string& block) {
    if (block.empty()) {
        return Family::None;
    }
    if (contains(block, "cobweb") || contains(block, "bamboo")) {
        return Family::Sword;
    }
    if (contains(block, "leaves") || contains(block, "wool") || contains(block, "vine") ||
        contains(block, "carpet")) {
        return Family::Shears;
    }
    if (contains(block, "hay_block") || contains(block, "sponge") || contains(block, "moss") ||
        contains(block, "target") || contains(block, "shroomlight") ||
        contains(block, "nether_wart_block") || contains(block, "dried_kelp")) {
        return Family::Hoe;
    }
    if (contains(block, "dirt") || contains(block, "grass_block") || contains(block, "sand") ||
        contains(block, "gravel") || contains(block, "clay") || contains(block, "snow") ||
        contains(block, "soul_soil") || contains(block, "soul_sand") || contains(block, "mud") ||
        contains(block, "podzol") || contains(block, "mycelium") || contains(block, "farmland")) {
        /* Sandstone is a pickaxe block that would otherwise be caught by the
         * "sand" test above. */
        if (!contains(block, "sandstone")) {
            return Family::Shovel;
        }
    }
    if (contains(block, "log") || contains(block, "planks") || contains(block, "wood") ||
        contains(block, "stem") || contains(block, "hyphae") || contains(block, "chest") ||
        contains(block, "barrel") || contains(block, "bookshelf") || contains(block, "sign") ||
        contains(block, "crafting_table") || contains(block, "ladder") ||
        contains(block, "pumpkin") || contains(block, "melon") || contains(block, "campfire") ||
        contains(block, "lectern") || contains(block, "beehive") || contains(block, "bee_nest") ||
        contains(block, "cartography") || contains(block, "fletching") ||
        contains(block, "smithing_table") || contains(block, "loom") ||
        contains(block, "composter") || contains(block, "banner")) {
        return Family::Axe;
    }
    if (contains(block, "stone") || contains(block, "ore") || contains(block, "obsidian") ||
        contains(block, "deepslate") || contains(block, "brick") || contains(block, "tuff") ||
        contains(block, "basalt") || contains(block, "blackstone") ||
        contains(block, "netherrack") || contains(block, "terracotta") ||
        contains(block, "concrete") || contains(block, "anvil") || contains(block, "furnace") ||
        contains(block, "amethyst") || contains(block, "calcite") || contains(block, "dripstone") ||
        contains(block, "iron_block") || contains(block, "gold_block") ||
        contains(block, "diamond_block") || contains(block, "netherite_block") ||
        contains(block, "copper") || contains(block, "rail") || contains(block, "hopper") ||
        contains(block, "cauldron") || contains(block, "prismarine") || contains(block, "purpur") ||
        contains(block, "quartz") || contains(block, "andesite") || contains(block, "diorite") ||
        contains(block, "granite") || contains(block, "sandstone")) {
        return Family::Pickaxe;
    }
    return Family::None;
}

Family familyOfTool(const std::string& item) {
    if (endsWith(item, "_pickaxe")) return Family::Pickaxe;
    if (endsWith(item, "_axe")) return Family::Axe;
    if (endsWith(item, "_shovel")) return Family::Shovel;
    if (endsWith(item, "_hoe")) return Family::Hoe;
    if (endsWith(item, "_sword")) return Family::Sword;
    if (item == "shears") return Family::Shears;
    return Family::None;
}

/* Vanilla ToolMaterial mining speeds. Gold is fastest by a distance, which is
 * exactly why it earns a hotbar slot in a build fight and nowhere else - the
 * module is answering "what breaks this soonest", which is what the setting
 * asks for. */
float materialSpeed(const std::string& item) {
    if (contains(item, "golden_")) return 12.0f;
    if (contains(item, "netherite_")) return 9.0f;
    if (contains(item, "diamond_")) return 8.0f;
    if (contains(item, "iron_")) return 6.0f;
    if (contains(item, "stone_")) return 4.0f;
    if (contains(item, "wooden_")) return 2.0f;
    if (item == "shears") return 5.0f;
    return 1.0f;
}

}  // namespace

AutoTool::AutoTool()
    : Module("AutoTool", "Swaps to the fastest tool for whatever you are breaking",
             Category::Player, 0) {
    setIcon(assets::IconAsset::Wrench);

    activation_ = &addNumber("Activation time", "Milliseconds the attack button must be held first",
                             0.0f, 0.0f, 1000.0f, 0);
    hoverDelay_ = &addNumber("Hover delay", "Milliseconds the crosshair must rest on the block",
                             0.0f, 0.0f, 1000.0f, 0);
    onlyCrouching_ =
        &addBoolean("Only while crouching", "Restrict the swap to a sneaking player", false);
    requireAttack_ = &addBoolean("Require mouse down", "Only while the attack button is held", true);
    switchBack_ =
        &addBoolean("Switch back when done", "Return to the previous slot afterwards", true);
    weapons_ = &addBoolean("Keep weapons", "Never swap away from a sword or an axe", false);
}

void AutoTool::onEnable(Context&) {
    wasAttacking_ = false;
    hovering_ = false;
    previousSlot_ = -1;
}

void AutoTool::onDisable(Context& context) { restore(context); }

void AutoTool::restore(Context& context) {
    if (previousSlot_ >= 0 && switchBack_->booleanValue() && context.state->player.valid) {
        game::Game::instance().setSelectedSlot(context.env, context.state->player.handle,
                                               previousSlot_);
    }
    previousSlot_ = -1;
}

void AutoTool::equip(Context& context, int slot) {
    game::Game& game = game::Game::instance();
    const int current = game.selectedSlot(context.env, context.state->player.handle);
    if (slot == current) {
        return;
    }
    /* Remembered once. Swapping between two tools while breaking a wall must
     * not overwrite the slot the player started on. */
    if (previousSlot_ < 0) {
        previousSlot_ = current;
    }
    game.setSelectedSlot(context.env, context.state->player.handle, slot);
}

int AutoTool::bestSlot(Context& context, const std::string& block) const {
    const Family wanted = familyFor(block);
    if (wanted == Family::None) {
        return -1;
    }
    game::Game& game = game::Game::instance();

    int best = -1;
    float bestSpeed = 0.0f;
    for (int slot = 0; slot <= 8; ++slot) {
        const game::Game::StackView stack =
            game.stack(context.env, context.state->player.handle, slot);
        if (!stack.present || familyOfTool(stack.id) != wanted) {
            continue;
        }
        const float speed = materialSpeed(stack.id);
        if (speed > bestSpeed) {
            bestSpeed = speed;
            best = slot;
        }
    }
    return best;
}

void AutoTool::onTick(Context& context) {
    const game::FrameState& state = *context.state;
    if (!state.inWorld || !state.player.valid || state.screenOpen) {
        restore(context);
        hovering_ = false;
        wasAttacking_ = false;
        return;
    }

    game::Game& game = game::Game::instance();

    const bool attacking = hooks::input().attackHeld();
    if (attacking && !wasAttacking_) {
        attackHeldSince_.reset();
    }
    wasAttacking_ = attacking;

    const game::CrosshairTarget target = game.crosshair(context.env);
    if (target.kind != game::CrosshairTarget::Kind::Block) {
        hovering_ = false;
        restore(context);
        return;
    }
    if (!hovering_) {
        hovering_ = true;
        hoverSince_.reset();
    }

    if (onlyCrouching_->booleanValue() && !state.player.sneaking) {
        restore(context);
        return;
    }
    if (requireAttack_->booleanValue() &&
        (!attacking ||
         !attackHeldSince_.elapsed(static_cast<std::int64_t>(activation_->number())))) {
        restore(context);
        return;
    }
    if (!hoverSince_.elapsed(static_cast<std::int64_t>(hoverDelay_->number()))) {
        restore(context);
        return;
    }
    /* Using an item is a different intent entirely - eating, drawing a bow,
     * placing - and a slot change would cancel it outright. */
    if (state.player.usingItem || hooks::input().useHeld()) {
        restore(context);
        return;
    }
    if (weapons_->booleanValue() && previousSlot_ < 0 &&
        game.holdingWeapon(context.env, state.player.handle)) {
        return;
    }

    jobject world = game.world(context.env);
    if (world == nullptr) {
        return;
    }
    const std::string block = game.blockId(context.env, world, target.x, target.y, target.z);
    context.env->DeleteLocalRef(world);

    const int slot = bestSlot(context, block);
    if (slot < 0) {
        return;
    }
    equip(context, slot);
}

}  // namespace cold::modules
