package cobble.v1_7.compat;

/** A block state property, read out of the metadata 1.7.10 keeps instead. */
@FunctionalInterface
public interface IProperty {
    @SuppressWarnings("rawtypes")
    Comparable read(IBlockState state);
}
