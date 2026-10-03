package cobble.module.combat.velocity;

import cobble.module.Module;
import cobble.module.Setting;
import cobble.module.shared.Facing;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.SplittableRandom;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S27PacketExplosion;

/**
 * Velocity's Normal mode: knockback is kept at a share of
 * itself, either straight away or a few ticks after it lands, and raised
 * instead while running from someone who is looking at you.
 */
public final class ScaledKnockback {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private final Setting chance;
    private final Setting horizontal;
    private final Setting vertical;
    private final Setting ticks;
    private final Setting kite;
    private final Setting kiteHorizontal;
    private final Setting kiteVertical;
    private final Setting alwaysKite;
    private final Setting onlyWhenTargeting;
    private final Setting waterCheck;

    private final SplittableRandom random = new SplittableRandom();
    private boolean pending;
    private boolean pendingVertical;
    private int ticksRemaining;

    public ScaledKnockback(Module module, BooleanSupplier shown) {
        chance = module.addNumber("Chance", "Chance of reducing knockback", 40, 0, 100, 0).visibleWhen(shown);
        horizontal = module.addNumber("Horizontal", "Share of the horizontal knockback you keep", 90, 0, 100, 0)
                .visibleWhen(shown);
        vertical = module.addNumber("Vertical", "Share of the vertical knockback you keep", 100, 0, 100, 0)
                .visibleWhen(shown);
        ticks = module.addNumber("Ticks", "How many ticks to wait before reducing; kite is never delayed", 1, 0, 10, 0)
                .visibleWhen(shown);
        kite = module.addBoolean("Kite mode", "Increases knockback while not facing the opponent", false)
                .visibleWhen(shown);
        BooleanSupplier kiting = () -> shown.getAsBoolean() && kite.bool();
        kiteHorizontal = module.addNumber("Kite horizontal", "Share of the horizontal knockback while kiting", 120, 100,
                300, 0).visibleWhen(kiting);
        kiteVertical = module.addNumber("Kite vertical", "Share of the vertical knockback while kiting", 120, 100,
                300, 0).visibleWhen(kiting);
        alwaysKite = module.addBoolean("Always Kite", "Increase knockback even while facing the opponent", false)
                .visibleWhen(kiting);
        onlyWhenTargeting = module.addBoolean("Only when targeting",
                "Only change knockback while face to face with an opponent", false).visibleWhen(shown);
        waterCheck = module.addBoolean("Water check", "Leave knockback alone in water", false).visibleWhen(shown);
    }

    public String suffix() {
        return horizontal.intValue() + "h " + vertical.intValue() + "v";
    }

    public void reset() {
        pending = false;
        ticksRemaining = 0;
    }

    /** True when the handler should not apply the packet itself, because the motion was set here. */
    public boolean velocity(S12PacketEntityVelocity packet) {
        EntityPlayerSP player = mc.thePlayer;
        if (skipped() || packet.getEntityID() != player.getEntityId() || !engaged(player) || !rolled()) {
            return false;
        }
        double motionX = packet.getMotionX() / 8000.0;
        double motionY = packet.getMotionY() / 8000.0;
        double motionZ = packet.getMotionZ() / 8000.0;
        if (kiting(player)) {
            double sideways = kiteHorizontal.number() / 100.0;
            player.setVelocity(motionX * sideways, motionY * kiteVertical.number() / 100.0, motionZ * sideways);
            return true;
        }
        if (ticks.intValue() > 0) {
            pending = true;
            pendingVertical = motionY != 0.0;
            ticksRemaining = ticks.intValue();
            return false;
        }
        double[] kept = keptShares();
        player.setVelocity(motionX * kept[0], motionY * kept[1], motionZ * kept[0]);
        return true;
    }

    public void explosion(S27PacketExplosion packet) {
        EntityPlayerSP player = mc.thePlayer;
        if (skipped() || !engaged(player) || !rolled()) {
            return;
        }
        float pushX = packet.func_149149_c();
        float pushY = packet.func_149144_d();
        float pushZ = packet.func_149147_e();
        double sideways;
        double upwards;
        if (kiting(player)) {
            sideways = kiteHorizontal.number() / 100.0;
            upwards = kiteVertical.number() / 100.0;
        } else if (ticks.intValue() > 0) {
            if (Math.abs(pushX) >= 0.005 || Math.abs(pushY) >= 0.005 || Math.abs(pushZ) >= 0.005) {
                pending = true;
                pendingVertical = pushY != 0.0f;
                ticksRemaining = ticks.intValue();
            }
            return;
        } else {
            double[] kept = keptShares();
            sideways = kept[0];
            upwards = kept[1];
        }
        packet.field_149152_f = (float) scale(pushX, sideways);
        packet.field_149153_g = (float) scale(pushY, upwards);
        packet.field_149159_h = (float) scale(pushZ, sideways);
    }

    /* Before the player's own update, so the cut lands on the motion it is about to move with. */
    public void tick() {
        if (skipped()) {
            reset();
            return;
        }
        if (!pending) {
            return;
        }
        if (ticksRemaining <= 0) {
            EntityPlayerSP player = mc.thePlayer;
            double[] kept = keptShares();
            if (pendingVertical && player.motionY > 0.0) {
                player.motionY = scale(player.motionY, kept[1]);
            }
            player.motionX = scale(player.motionX, kept[0]);
            player.motionZ = scale(player.motionZ, kept[0]);
            pending = false;
        }
        --ticksRemaining;
    }

    private boolean skipped() {
        EntityPlayerSP player = mc.thePlayer;
        return player == null || waterCheck.bool() && player.isInWater();
    }

    /* Only when targeting skips the hit unless someone is in the fight with you; kiting needs no target of yours. */
    private boolean engaged(EntityPlayerSP player) {
        return kite.bool() || !onlyWhenTargeting.bool() || Facing.opponentFacing(player)
                || Facing.facingOpponent(player);
    }

    private boolean kiting(EntityPlayerSP player) {
        return kite.bool() && Facing.opponentFacing(player) && (alwaysKite.bool() || !Facing.facingOpponent(player));
    }

    private boolean rolled() {
        return random.nextInt(0, 100) >= 100 - chance.number();
    }

    /* A few percent of jitter on top, so the kept share is never the same twice; a near-full vertical is full. */
    private double[] keptShares() {
        double jitter = random.nextDouble();
        double sideways = horizontal.number();
        double upwards = vertical.number();
        if (sideways > 0.0) {
            sideways = Math.min(100.0, sideways + 5.0 * jitter);
        }
        if (upwards > 0.0) {
            upwards += 5.0 * jitter;
            if (upwards >= 90.0) {
                upwards = 100.0;
            }
        }
        return new double[] {sideways / 100.0, upwards / 100.0};
    }

    /* Cut to the decimals the motion arrived with, so a scaled value reads like one the server could have sent. */
    private static double scale(double motion, double share) {
        String text = Double.toString(Math.abs(motion));
        int places = Math.max(0, text.length() - text.indexOf('.') - 1);
        return BigDecimal.valueOf(motion * share).setScale(places, RoundingMode.DOWN).doubleValue();
    }
}
