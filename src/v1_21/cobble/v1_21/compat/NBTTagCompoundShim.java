package cobble.v1_21.compat;

import net.minecraft.nbt.NbtCompound;

/** 1.8.9's NBTTagCompound on 1.21.11's NbtCompound. */
public final class NBTTagCompoundShim {
    private NBTTagCompoundShim() {}

    public static void removeTag(NbtCompound compound, String key) {
        compound.remove(key);
    }
}
