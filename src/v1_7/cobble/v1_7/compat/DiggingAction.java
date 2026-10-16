package cobble.v1_7.compat;

/** C07PacketPlayerDigging's 1.8.9 actions, in the order of the status numbers 1.7.10 sends instead. */
public enum DiggingAction {
    START_DESTROY_BLOCK, ABORT_DESTROY_BLOCK, STOP_DESTROY_BLOCK, DROP_ALL_ITEMS, DROP_ITEM, RELEASE_USE_ITEM
}
