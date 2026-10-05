package cobble.module.render;

import cobble.core.Time;
import cobble.event.EventBus;
import cobble.event.GameLoopEvent;
import cobble.event.Subscribe;
import cobble.module.Category;
import cobble.module.Context;
import cobble.module.LegacyModule;
import cobble.module.Setting;
import cobble.module.Subcategory;
import java.util.List;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;

/**
 * Lights up the dark, through a night vision effect the server never hears of
 * or by pushing the brightness slider far past its end. Fade eases the
 * brightness in and out instead of snapping it.
 */
public final class Fullbright extends LegacyModule {
    private static final int NIGHT_VISION = 0;
    private static final float FULL_GAMMA = 10.0f;
    private static final float FADE_STEP = 0.4f;
    private static final long FADE_INTERVAL_MS = 10;
    /* Past the four-minute mark, so the effect never starts blinking out. */
    private static final int EFFECT_TICKS = 5220;

    private final Setting mode = addChoice("Mode", "Night vision, or the brightness slider pushed past its end",
            List.of("Night Vision", "Gamma"), NIGHT_VISION);
    private final Setting fade = addBoolean("Fade", "Brightness changes fade in and out", false)
            .visibleWhen(() -> mode.choice() != NIGHT_VISION);

    private float originalGamma = -1.0f;
    /* The gamma still being eased toward, or NaN when nothing is fading. Runs while the module is off as well. */
    private float fadeTarget = Float.NaN;
    private long lastFadeStep;

    public Fullbright() {
        super("Fullbright", "Lights up the dark", Category.VISUALS, Subcategory.BASIC);
        EventBus.register(new Object() {
            @Subscribe
            public void onGameLoop(GameLoopEvent event) {
                stepFade();
            }
        });
    }

    @Override
    public void onEnable(Context context) {
        if (Float.isNaN(fadeTarget)) {
            originalGamma = mc.gameSettings.gammaSetting;
        }
        if (mode.choice() != NIGHT_VISION) {
            brighten();
        }
    }

    @Override
    public void onDisable(Context context) {
        removeNightVision();
        if (mode.choice() != NIGHT_VISION && fade.bool()) {
            fadeTarget = originalGamma;
        } else {
            fadeTarget = Float.NaN;
            mc.gameSettings.gammaSetting = originalGamma;
        }
    }

    @Override
    protected void settingChanged(Setting setting) {
        if (setting != mode || !enabled()) {
            return;
        }
        if (mode.choice() == NIGHT_VISION) {
            fadeTarget = Float.NaN;
            mc.gameSettings.gammaSetting = originalGamma;
        } else {
            removeNightVision();
            brighten();
        }
    }

    @Override
    public void onTick(Context context) {
        if (mode.choice() == NIGHT_VISION && mc.thePlayer != null) {
            mc.thePlayer.addPotionEffect(new PotionEffect(Potion.nightVision.id, EFFECT_TICKS, 0));
        }
    }

    private void brighten() {
        if (fade.bool()) {
            fadeTarget = FULL_GAMMA;
        } else {
            mc.gameSettings.gammaSetting = FULL_GAMMA;
        }
    }

    private void stepFade() {
        long now = Time.nowMillis();
        if (Float.isNaN(fadeTarget) || now - lastFadeStep < FADE_INTERVAL_MS) {
            return;
        }
        lastFadeStep = now;
        float gamma = mc.gameSettings.gammaSetting;
        float next = gamma < fadeTarget ? Math.min(fadeTarget, gamma + FADE_STEP)
                : Math.max(fadeTarget, gamma - FADE_STEP);
        mc.gameSettings.gammaSetting = next;
        if (next == fadeTarget) {
            fadeTarget = Float.NaN;
        }
    }

    private void removeNightVision() {
        if (mc.thePlayer != null) {
            mc.thePlayer.removePotionEffectClient(Potion.nightVision.id);
        }
    }
}
