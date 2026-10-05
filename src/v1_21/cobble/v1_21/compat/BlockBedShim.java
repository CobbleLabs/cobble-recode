package cobble.v1_21.compat;

import net.minecraft.block.BedBlock;
import net.minecraft.block.enums.BedPart;
import net.minecraft.state.property.EnumProperty;

/** 1.8.9's BlockBed properties on 1.21.11's BedBlock. */
public final class BlockBedShim {
    private BlockBedShim() {}

    public static EnumProperty<BedPart> PART() {
        return BedBlock.PART;
    }
}
