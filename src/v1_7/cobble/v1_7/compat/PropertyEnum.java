package cobble.v1_7.compat;

import java.util.function.Function;

public class PropertyEnum implements IProperty {
    private final Function<IBlockState, Enum<?>> reader;

    PropertyEnum(Function<IBlockState, Enum<?>> reader) {
        this.reader = reader;
    }

    @SuppressWarnings("rawtypes")
    @Override
    public Comparable read(IBlockState state) {
        return reader.apply(state);
    }
}
