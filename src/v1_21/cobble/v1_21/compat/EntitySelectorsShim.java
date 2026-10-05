package cobble.v1_21.compat;

import com.google.common.base.Predicate;
import net.minecraft.entity.Entity;
import net.minecraft.predicate.entity.EntityPredicates;

/** 1.8.9's EntitySelectors as Guava predicates over 1.21.11's EntityPredicates. */
public final class EntitySelectorsShim {
    private static final Predicate<Entity> NOT_SPECTATING = EntityPredicates.EXCEPT_SPECTATOR::test;

    private EntitySelectorsShim() {}

    public static Predicate<Entity> NOT_SPECTATING() {
        return NOT_SPECTATING;
    }
}
