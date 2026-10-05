package cobble.event;

/** The local player about to attack; the entities are the game's own objects. */
public final class AttackEntityEvent extends Event {
    public final Object entity;
    public final Object entityPlayer;
    public final boolean isPreAttack;

    public AttackEntityEvent(Object entity, Object entityPlayer, boolean isPreAttack) {
        this.entity = entity;
        this.entityPlayer = entityPlayer;
        this.isPreAttack = isPreAttack;
    }
}
