package cobble.game;

import cobble.core.Vec3;
import net.minecraft.client.entity.EntityPlayerSP;

public final class LocalPlayerView {
    public boolean valid;
    public EntityPlayerSP handle;

    public Vec3 pos = Vec3.ZERO;
    public Vec3 eyePos = Vec3.ZERO;
    public Vec3 velocity = Vec3.ZERO;
    public float yaw;
    public float pitch;
    public float lastYaw;
    public float lastPitch;
    public float eyeHeight = 1.62f;

    public float health;
    public float maxHealth = 20.0f;
    /* Armour points. Anything estimating what a hit will do to you has to read
     * this, or every self-damage limit measures you as though you were naked. */
    public int armor;
    public boolean onGround;
    public boolean sneaking;
    public boolean sprinting;
    public boolean jumping;
    /* Counts down from maxHurtTime after a hit lands. A rising edge on this is
     * the only client-side signal that the player just took knockback, which is
     * what every reset - jump, w-tap, clutch - is timed against. */
    public int hurtTime;
    public int maxHurtTime = 10;
    public double fallDistance;
    public boolean usingItem;
    /* A bow's power is a function of exactly this. */
    public int itemUseTicks;
    public int age;
}
