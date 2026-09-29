package cobble.module.shared;

import cobble.module.Module;

public final class Idctho {
    private final Module module;
    private boolean everDisabled;

    public Idctho(Module var1) {
        this.module = var1;
        this.everDisabled = !var1.enabled();
    }

    public boolean hasEverBeenDisabled() {
        this.everDisabled = this.everDisabled | !this.module.enabled();
        return this.everDisabled;
    }
}
