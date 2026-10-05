package cobble.v1_7.compat;

/** Which of position, colour and texture a 1.8.9 vertex format carries; 1.7.10 takes them per vertex. */
public final class VertexFormat {
    final boolean textured;

    VertexFormat(boolean textured) {
        this.textured = textured;
    }
}
