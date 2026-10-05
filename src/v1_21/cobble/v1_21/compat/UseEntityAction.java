package cobble.v1_21.compat;

/** 1.8.9's C02PacketUseEntity.Action, in its order; 1.21.11 keeps the action behind a handler instead. */
public enum UseEntityAction {
    INTERACT,
    ATTACK,
    INTERACT_AT
}
