package cobble.v1_21.compat;

/** 1.8.9's vertex formats the legacy code draws with. */
public final class DefaultVertexFormats {
    public static final VertexFormat POSITION = new VertexFormat(false);
    public static final VertexFormat POSITION_COLOR = new VertexFormat(false);
    public static final VertexFormat POSITION_TEX = new VertexFormat(true);

    private DefaultVertexFormats() {}
}
