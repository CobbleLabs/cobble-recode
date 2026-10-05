package cobble.v1_21.compat;

/** 1.8.9's C16PacketClientStatus.EnumState, in its order; 1.21.11 has no state for opening the inventory. */
public enum ClientStatusMode {
    PERFORM_RESPAWN,
    REQUEST_STATS,
    OPEN_INVENTORY_ACHIEVEMENT
}
