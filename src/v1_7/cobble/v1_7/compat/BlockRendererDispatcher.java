package cobble.v1_7.compat;

/** 1.8.9's block renderer, of which 1.7.10 only has what its model shapes stand for. */
public final class BlockRendererDispatcher {
    static final BlockRendererDispatcher INSTANCE = new BlockRendererDispatcher();

    private final BlockModelShapes shapes = new BlockModelShapes();

    private BlockRendererDispatcher() {}

    public BlockModelShapes getBlockModelShapes() {
        return shapes;
    }
}
