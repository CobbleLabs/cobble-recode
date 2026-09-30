#pragma once

#include <string>
#include <unordered_map>
#include <vector>

#include "bridge/Minecraft.hpp"

namespace cold::game {

/* Everything the client is allowed to know about the other players on the
 * server, kept across the moments it is allowed to know it.
 *
 * A player's equipment only reaches the client while they are in a loaded
 * chunk, and only the six slots the server chose to send. Held for one frame
 * that is almost useless - somebody walks past with a sword out and the sword is
 * all you ever saw. Held across every sighting it becomes an inventory: the
 * totem they swapped to, the pick they mined with, the armour they changed
 * into. That accumulation is the whole point of this file, and it is the reason
 * the record is keyed by UUID rather than by entity id - an entity id is
 * recycled the moment a player leaves render distance.
 *
 * Nothing here asks the server for anything. Every field is a read of state the
 * vanilla client already holds so it can draw the player. */

struct RadarItem {
    std::string id;    // registry path
    std::string name;  // display name at the time it was seen
    /* The largest stack of it seen at once, which is the honest answer to "how
     * many do they have" from outside their inventory. */
    int count = 1;
    int sightings = 0;
    int damage = 0;
    int maxDamage = 0;
    /* Which slot it was last seen in, so armour sorts above hotbar clutter. */
    int slot = -1;
    float lastSeen = 0.0f;
    std::vector<Game::EnchantView> enchantments;
};

struct RadarPlayer {
    std::string uuid;
    std::string name;
    /* You. Kept in the same list so the window is never empty and so your own
     * kit is one glance away, sorted to the top and labelled. */
    bool self = false;

    /* In a loaded chunk right now. A record outlives this. */
    bool loaded = false;
    int entityId = -1;
    float firstSeen = 0.0f;
    float lastSeen = 0.0f;

    Vec3 pos;
    double distance = 0.0;
    float health = 0.0f;
    float maxHealth = 20.0f;
    float absorption = 0.0f;
    int armor = 0;
    int ping = -1;

    Game::EquipmentView slots[static_cast<int>(Game::Slot::Count)];
    std::vector<Game::EffectView> effects;
    std::vector<RadarItem> history;
    Game::SkinView skin;
};

class PlayerRadar {
public:
    static PlayerRadar& instance();

    /* Called on the tick edge. `budget` is how many players may have their
     * equipment re-read this tick: the whole point of the round robin is that
     * six equipment reads with their enchantment sets cost about thirty JNI
     * calls each, and forty players every tick would be twenty-four thousand a
     * second for information that changes once a minute. `focus` is the UUID of
     * whoever the window is showing, which is always refreshed. */
    void update(JNIEnv* env, const FrameState& state, float time, int budget,
                const std::string& focus);

    /* Newest snapshot, unsorted. The window sorts its own copy. */
    const std::vector<RadarPlayer>& players() const { return players_; }
    const RadarPlayer* find(const std::string& uuid) const;
    int loadedCount() const { return loaded_; }

    void clear();

private:
    PlayerRadar() = default;

    RadarPlayer& record(const std::string& uuid, float time);
    void readEquipment(JNIEnv* env, RadarPlayer& player, jobject handle, float time);
    void remember(RadarPlayer& player, const Game::EquipmentView& item, int slot, float time);

    std::vector<RadarPlayer> players_;
    std::unordered_map<std::string, std::size_t> index_;
    /* Entity id -> UUID, so the per-frame walk costs no JNI for a player that
     * is already known. */
    std::unordered_map<int, std::string> byEntity_;
    std::string selfUuid_;
    std::size_t cursor_ = 0;
    int loaded_ = 0;
    int tick_ = 0;
};

inline PlayerRadar& radar() { return PlayerRadar::instance(); }

}  // namespace cold::game
