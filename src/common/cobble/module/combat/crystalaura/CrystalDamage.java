package cobble.module.combat.crystalaura;

import cobble.core.Vec3;
import cobble.game.Game;
import cobble.game.Raycast;

/**
 * What a crystal going off at a point does to an entity, after 1.21's
 * explosion, armour, Protection and Resistance. Exposure is sampled on a
 * coarser grid than the game's, which is close enough to rank placements.
 */
public final class CrystalDamage {
    private static final double POWER = 6.0;
    private static final int SAMPLES_XZ = 3;
    private static final int SAMPLES_Y = 4;
    private static final double SURFACE = 0.01;

    private CrystalDamage() {}

    /** An entity as the damage sees it; its gear and effects are read through the handle. */
    public record Victim(Object handle, Vec3 boxMin, Vec3 boxMax, int armor) {}

    public static float damage(Game game, Victim victim, Vec3 blast) {
        Vec3 centre = new Vec3((victim.boxMin.x() + victim.boxMax.x()) * 0.5, victim.boxMin.y(),
                (victim.boxMin.z() + victim.boxMax.z()) * 0.5);
        double reach = POWER * 2.0;
        double distance = centre.sub(blast).length() / reach;
        if (distance > 1.0) {
            return 0.0f;
        }
        double impact = (1.0 - distance) * exposure(victim, blast);
        float raw = (float) ((impact * impact + impact) / 2.0 * 7.0 * reach + 1.0);
        return afterProtection(game, victim, afterArmor(raw, victim.armor, toughness(game, victim.handle)));
    }

    /* The share of points on the box the blast reaches without a block in the way. */
    private static double exposure(Victim victim, Vec3 blast) {
        int open = 0;
        int total = 0;
        for (int i = 0; i < SAMPLES_XZ; ++i) {
            for (int j = 0; j < SAMPLES_Y; ++j) {
                for (int k = 0; k < SAMPLES_XZ; ++k) {
                    Vec3 point = new Vec3(
                            lerp(victim.boxMin.x(), victim.boxMax.x(), i / (double) (SAMPLES_XZ - 1)),
                            lerp(victim.boxMin.y(), victim.boxMax.y(), j / (double) (SAMPLES_Y - 1)),
                            lerp(victim.boxMin.z(), victim.boxMax.z(), k / (double) (SAMPLES_XZ - 1)));
                    Vec3 towards = blast.sub(point);
                    double length = towards.length();
                    ++total;
                    /* Short of the blast, which sits on the face of the block under the crystal. */
                    if (length < SURFACE || Raycast.raycastBlocks(point, towards.mul(1.0 / length),
                            length - SURFACE) == null) {
                        ++open;
                    }
                }
            }
        }
        return open / (double) total;
    }

    private static double lerp(double from, double to, double t) {
        return from + (to - from) * t;
    }

    private static float afterArmor(float damage, int armor, float toughness) {
        float reduction = Math.max(armor * 0.2f, Math.min(20.0f, armor - damage / (2.0f + toughness / 4.0f)));
        return damage * (1.0f - reduction / 25.0f);
    }

    private static float toughness(Game game, Object handle) {
        float toughness = 0.0f;
        for (Game.Slot slot : new Game.Slot[] {Game.Slot.HEAD, Game.Slot.CHEST, Game.Slot.LEGS, Game.Slot.FEET}) {
            String id = game.equipment(handle, slot).id();
            if (id.startsWith("netherite_")) {
                toughness += 3.0f;
            } else if (id.startsWith("diamond_")) {
                toughness += 2.0f;
            }
        }
        return toughness;
    }

    /* Protection counts one a level, Blast Protection two, up to 20 points of the 25; Resistance a fifth a level. */
    private static float afterProtection(Game game, Victim victim, float damage) {
        int points = 0;
        for (Game.Slot slot : new Game.Slot[] {Game.Slot.HEAD, Game.Slot.CHEST, Game.Slot.LEGS, Game.Slot.FEET}) {
            for (Game.EnchantView enchant : game.equipment(victim.handle, slot).enchantments()) {
                if (enchant.id().equals("protection")) {
                    points += enchant.level();
                } else if (enchant.id().equals("blast_protection")) {
                    points += enchant.level() * 2;
                }
            }
        }
        damage *= 1.0f - Math.min(20, points) / 25.0f;
        for (Game.EffectView effect : game.statusEffects(victim.handle)) {
            if (effect.id().equals("resistance")) {
                damage *= Math.max(0.0f, 1.0f - (effect.amplifier() + 1) * 0.2f);
            }
        }
        return damage;
    }
}
