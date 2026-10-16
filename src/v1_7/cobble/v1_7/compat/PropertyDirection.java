package cobble.v1_7.compat;

import java.util.function.Function;

public final class PropertyDirection extends PropertyEnum {
    PropertyDirection(Function<IBlockState, Enum<?>> reader) {
        super(reader);
    }
}
