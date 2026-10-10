package cobble.module;

import cobble.core.Color;
import cobble.core.Log;
import cobble.event.DrawEvent;
import cobble.event.Subscribe;
import cobble.render.Icon;
import cobble.ui.Client;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public abstract class Module {
    private static final float ANIMATION_RATE = 11.0f;

    public final String name;
    public final String description;
    public final Category category;
    public final Subcategory subcategory;
    private Icon icon;
    private int key;
    private boolean enabled;
    private boolean running;
    private float animation;
    private final List<Setting> settings = new ArrayList<>();
    private Setting streamProof;

    protected Module(String name, String description, Category category, Subcategory subcategory) {
        this(name, description, category, subcategory, 0);
    }

    protected Module(String name, String description, Category category, Subcategory subcategory, int defaultKey) {
        this.name = name;
        this.description = description;
        this.category = category;
        this.subcategory = subcategory;
        this.icon = category.icon;
        this.key = defaultKey;
    }

    public Icon icon() {
        return icon;
    }

    protected void setIcon(Icon value) {
        icon = value;
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * A null context flips the flag without the callbacks: config is applied
     * before the first tick, and the next in-world tick runs whichever
     * callback the flag still owes.
     */
    public void setEnabled(boolean value, Context context) {
        if (enabled == value) {
            return;
        }
        enabled = value;
        ModuleManager.markChanged();
        Log.debug("module", "%s %s", name, value ? "enabled" : "disabled");
        if (context != null) {
            run(value, context);
            if (announcesToggle()) {
                Client.hud().moduleToggled(this);
            }
        }
    }

    /** A held bind switches on every press, which is too often for a notice. */
    protected boolean announcesToggle() {
        return !holdsKey();
    }

    void sync(Context context) {
        if (running != enabled) {
            run(enabled, context);
        }
    }

    private void run(boolean value, Context context) {
        running = value;
        if (value) {
            onEnable(context);
        } else {
            onDisable(context);
        }
    }

    public void toggle(Context context) {
        setEnabled(!enabled, context);
    }

    public int key() {
        return key;
    }

    public void setKey(int value) {
        key = value;
        ModuleManager.markChanged();
    }

    /** A held bind keeps the module on only while it is down, instead of toggling per press. */
    public boolean holdsKey() {
        return false;
    }

    public void keyHeld(boolean held, Context context) {
        setEnabled(held, context);
    }

    /**
     * 0..1 fade used by the HUD list and the menu toggle, driven centrally so
     * every surface animates in step.
     */
    public float animation() {
        return animation;
    }

    void advanceAnimation(float deltaTime) {
        float target = enabled ? 1.0f : 0.0f;
        animation += (target - animation) * (1.0f - (float) Math.exp(-ANIMATION_RATE * deltaTime));
        if (Math.abs(target - animation) < 0.002f) {
            animation = target;
        }
    }

    /** Extra text shown beside the name in the array list, such as the active mode. */
    public String suffix() {
        return "";
    }

    public void onEnable(Context context) {}

    public void onDisable(Context context) {}

    public void onTick(Context context) {}

    public void onRender(Context context) {}

    /** At the end of every game tick while in a world, after the player has moved. */
    public void onUpdate() {}

    /** Every game tick while the menu is open, to show or hide settings that depend on others. */
    public void guiUpdate() {}

    /** Whether the running version has what the module works with; one that does not is never registered. */
    public boolean supported() {
        return true;
    }

    /** Drawn for the player but kept off screen captures; see {@link cobble.render.StreamProof}. */
    public boolean streamProof() {
        return streamProof != null && streamProof.bool();
    }

    /**
     * Whether everything the module shows comes from the passes the stream proof
     * layer can take over: {@link #onRender} and its {@link DrawEvent} handlers.
     * A module that changes how the game draws the world itself must say no.
     */
    protected boolean canStreamProof() {
        if (category.tab != Category.VISUALS) {
            return false;
        }
        try {
            if (getClass().getMethod("onRender", Context.class).getDeclaringClass() != Module.class) {
                return true;
            }
        } catch (NoSuchMethodException impossible) {
            throw new IllegalStateException(impossible);
        }
        // The same methods EventBus.register subscribes.
        for (Method method : getClass().getDeclaredMethods()) {
            if (method.isAnnotationPresent(Subscribe.class) && method.getParameterCount() == 1
                    && DrawEvent.class.isAssignableFrom(method.getParameterTypes()[0])) {
                return true;
            }
        }
        return false;
    }

    void offerStreamProof() {
        if (canStreamProof()) {
            streamProof = addBoolean("Stream Proof", "Kept out of OBS, Discord and other captures; you still see it",
                    false);
        }
    }

    /** Whether this module is in the middle of clicking the inventory, which no other click may interleave with. */
    public boolean ownsInventory() {
        return false;
    }

    public List<Setting> settings() {
        return Collections.unmodifiableList(settings);
    }

    public Setting setting(String settingName) {
        for (Setting setting : settings) {
            if (setting.name.equals(settingName)) {
                return setting;
            }
        }
        return null;
    }

    /** After a setting's value changes, from the menu, a script or the config. */
    protected void settingChanged(Setting setting) {}

    private Setting track(Setting setting) {
        setting.onChange = () -> {
            settingChanged(setting);
            ModuleManager.markChanged();
        };
        settings.add(setting);
        return setting;
    }

    public Setting addBoolean(String settingName, String settingDescription, boolean value) {
        Setting setting = new Setting(Setting.Type.BOOLEAN, settingName, settingDescription);
        setting.setBool(value);
        return track(setting);
    }

    public Setting addNumber(String settingName, String settingDescription, float value, float minimum,
                                float maximum, int decimals) {
        Setting setting = new Setting(Setting.Type.NUMBER, settingName, settingDescription);
        setting.minimum = minimum;
        setting.maximum = maximum;
        setting.decimals = decimals;
        setting.step = decimals <= 0 ? 1.0f : (float) Math.pow(0.1, decimals);
        setting.setNumber(value);
        return track(setting);
    }

    public Setting addChoice(String settingName, String settingDescription, List<String> choices, int index) {
        Setting setting = new Setting(Setting.Type.CHOICE, settingName, settingDescription);
        setting.choices = List.copyOf(choices);
        setting.setChoice(index);
        return track(setting);
    }

    public Setting addKey(String settingName, String settingDescription, int value) {
        Setting setting = new Setting(Setting.Type.KEY, settingName, settingDescription);
        setting.setKey(value);
        settings.add(setting);
        return setting;
    }

    public Setting addColour(String settingName, String settingDescription, Color colour) {
        Setting setting = new Setting(Setting.Type.COLOUR, settingName, settingDescription);
        setting.setColour(colour);
        settings.add(setting);
        return setting;
    }
}
