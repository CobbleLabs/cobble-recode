package cobble.v1_7.compat;

import com.google.common.base.Predicate;
import net.minecraft.entity.Entity;

/** There are no spectators on 1.7.10, so every entity is not spectating. */
public final class EntitySelectors {
    public static final Predicate<Entity> NOT_SPECTATING = entity -> true;

    private EntitySelectors() {}
}
