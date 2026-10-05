package cobble.v1_21.compat;

import net.minecraft.nbt.NbtList;

/** 1.8.9's NBTTagList on 1.21.11's NbtList. */
public final class NBTTagListShim {
    private NBTTagListShim() {}

    public static int tagCount(NbtList list) {
        return list.size();
    }
}
