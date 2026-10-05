package cobble.v1_7.compat;

public final class DefaultVertexFormats {
    public static final VertexFormat POSITION = new VertexFormat(false);
    public static final VertexFormat POSITION_COLOR = new VertexFormat(false);
    public static final VertexFormat POSITION_TEX = new VertexFormat(true);

    private DefaultVertexFormats() {}
}
