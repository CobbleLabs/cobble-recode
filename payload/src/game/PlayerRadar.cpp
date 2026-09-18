#include "game/PlayerRadar.hpp"

#include <algorithm>
#include <cmath>

namespace cold::game {
namespace {

constexpr float kForget = 900.0f;
/* Ticks between refreshes of a player's ping. The tab list updates it about
 * once a second and nothing reads it faster than a person can. */
constexpr int kPingInterval = 40;
constexpr std::size_t kMaxHistory = 48;

}  // namespace

PlayerRadar& PlayerRadar::instance() {
    static PlayerRadar radar;
    return radar;
}

void PlayerRadar::clear() {
    players_.clear();
    index_.clear();
    byEntity_.clear();
    cursor_ = 0;
    loaded_ = 0;
}

const RadarPlayer* PlayerRadar::find(const std::string& uuid) const {
    const auto found = index_.find(uuid);
    return found == index_.end() ? nullptr : &players_[found->second];
}

RadarPlayer& PlayerRadar::record(const std::string& uuid, float time) {
    const auto found = index_.find(uuid);
    if (found != index_.end()) {
        return players_[found->second];
    }
    RadarPlayer entry;
    entry.uuid = uuid;
    entry.firstSeen = time;
    players_.push_back(std::move(entry));
    index_.emplace(uuid, players_.size() - 1);
    return players_.back();
}

/* One sighting folded into the history.
 *
 * A repeat of something already known updates the count and the wear rather
 * than adding a row: the list is meant to read as "what this player has", and a
 * diamond sword seen forty times is one sword. */
void PlayerRadar::remember(RadarPlayer& player, const Game::EquipmentView& item, int slot,
                           float time) {
    if (!item.present || item.id.empty()) {
        return;
    }
    for (RadarItem& known : player.history) {
        if (known.id != item.id) {
            continue;
        }
        /* Two different swords are two rows. Anything else - a rename, a
         * different enchant set - is a different item to the person reading
         * this, so it earns its own line. */
        if (known.enchantments.size() == item.enchantments.size() && known.name == item.name) {
            bool same = true;
            for (std::size_t index = 0; index < known.enchantments.size(); ++index) {
                if (known.enchantments[index].id != item.enchantments[index].id ||
                    known.enchantments[index].level != item.enchantments[index].level) {
                    same = false;
                    break;
                }
            }
            if (same) {
                known.count = std::max(known.count, item.count);
                known.damage = item.damage;
                known.maxDamage = item.maxDamage;
                known.lastSeen = time;
                known.slot = slot;
                ++known.sightings;
                return;
            }
        }
    }
    if (player.history.size() >= kMaxHistory) {
        return;
    }
    RadarItem fresh;
    fresh.id = item.id;
    fresh.name = item.name;
    fresh.count = item.count;
    fresh.damage = item.damage;
    fresh.maxDamage = item.maxDamage;
    fresh.enchantments = item.enchantments;
    fresh.slot = slot;
    fresh.sightings = 1;
    fresh.lastSeen = time;
    player.history.push_back(std::move(fresh));
}

void PlayerRadar::readEquipment(JNIEnv* env, RadarPlayer& player, jobject handle, float time) {
    Game& game = Game::instance();
    for (int slot = 0; slot < static_cast<int>(Game::Slot::Count); ++slot) {
        Game::EquipmentView item = game.equipment(env, handle, static_cast<Game::Slot>(slot));
        remember(player, item, slot, time);
        player.slots[slot] = std::move(item);
    }
    player.effects = game.statusEffects(env, handle);
}

void PlayerRadar::update(JNIEnv* env, const FrameState& state, float time, int budget,
                         const std::string& focus) {
    ++tick_;
    Game& game = Game::instance();

    for (RadarPlayer& player : players_) {
        player.loaded = false;
    }

    loaded_ = 0;
    /* Whoever is being looked at is refreshed every tick and does not come out
     * of the round robin's budget; the rest take turns. */
    /* Indices, not pointers: record() can grow the vector and move every one
     * of them. */
    std::vector<std::size_t> waiting;

    /* The capture deliberately leaves the local player out of the entity list,
     * so they are added here. Their equipment is read every tick: it is one
     * player, it changes constantly, and it is the one row that is always
     * worth being right. */
    if (state.player.valid && state.player.handle != nullptr) {
        if (selfUuid_.empty()) {
            selfUuid_ = game.entityUuid(env, state.player.handle);
        }
        if (!selfUuid_.empty()) {
            RadarPlayer& me = record(selfUuid_, time);
            if (me.name.empty()) {
                me.name = game.entityName(env, state.player.handle);
            }
            me.self = true;
            me.loaded = true;
            me.entityId = -1;
            me.lastSeen = time;
            me.pos = state.player.pos;
            me.distance = 0.0;
            me.health = state.player.health;
            me.maxHealth = state.player.maxHealth;
            me.armor = state.player.armor;
            me.ping = state.ping;
            if (me.skin.texture == 0) {
                me.skin = game.playerSkin(env, state.player.handle);
            }
            readEquipment(env, me, state.player.handle, time);
            ++loaded_;
        }
    }

    for (const EntityView& entity : state.entities) {
        if (!entity.isPlayer || entity.handle == nullptr) {
            continue;
        }
        std::string uuid;
        const auto cachedName = byEntity_.find(entity.id);
        if (cachedName != byEntity_.end()) {
            uuid = cachedName->second;
        } else {
            uuid = game.entityUuid(env, entity.handle);
            if (uuid.empty()) {
                continue;
            }
            byEntity_.emplace(entity.id, uuid);
        }

        RadarPlayer& player = record(uuid, time);
        if (player.name.empty()) {
            player.name = game.entityName(env, entity.handle);
        }
        player.loaded = true;
        player.entityId = entity.id;
        player.lastSeen = time;
        player.pos = entity.pos;
        player.distance = entity.distance;
        player.health = entity.health;
        player.maxHealth = entity.maxHealth;
        player.absorption = entity.absorption;
        player.armor = entity.armor;
        ++loaded_;

        /* The skin is one texture the game uploaded once; it is asked for again
         * only while it has not arrived. */
        if (player.skin.texture == 0) {
            player.skin = game.playerSkin(env, entity.handle);
        }
        if (player.ping < 0 || (tick_ + entity.id) % kPingInterval == 0) {
            player.ping = game.entityLatency(env, entity.handle);
        }

        if (!focus.empty() && uuid == focus) {
            readEquipment(env, player, entity.handle, time);
        } else {
            waiting.push_back(index_.at(uuid));
        }
    }

    /* Round robin over the rest, starting where the last tick stopped so nobody
     * is starved by their position in the entity list. */
    if (!waiting.empty()) {
        const std::size_t count = waiting.size();
        for (int spent = 0; spent < budget && spent < static_cast<int>(count); ++spent) {
            RadarPlayer& player = players_[waiting[(cursor_ + static_cast<std::size_t>(spent)) % count]];
            for (const EntityView& entity : state.entities) {
                if (entity.id == player.entityId && entity.handle != nullptr) {
                    readEquipment(env, player, entity.handle, time);
                    break;
                }
            }
        }
        cursor_ = (cursor_ + static_cast<std::size_t>(std::max(budget, 1))) % count;
    }

    /* Entity ids are recycled, so the map is dropped for anything no longer in
     * the world rather than left to point at somebody else. */
    for (auto it = byEntity_.begin(); it != byEntity_.end();) {
        const RadarPlayer* player = find(it->second);
        it = (player == nullptr || !player->loaded || player->entityId != it->first)
                 ? byEntity_.erase(it)
                 : std::next(it);
    }

    /* A record that has not been seen for a quarter of an hour is somebody who
     * left the server. Keeping it forever would turn the list into a log. */
    bool dropped = false;
    for (auto it = players_.begin(); it != players_.end();) {
        if (!it->loaded && time - it->lastSeen > kForget) {
            it = players_.erase(it);
            dropped = true;
        } else {
            ++it;
        }
    }
    if (dropped) {
        index_.clear();
        for (std::size_t position = 0; position < players_.size(); ++position) {
            index_.emplace(players_[position].uuid, position);
        }
        cursor_ = 0;
    }
}

}  // namespace cold::game
