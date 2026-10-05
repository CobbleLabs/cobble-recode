package cobble.v1_21.compat;

/** 1.8.9's vertex format; the emulator only needs to know whether vertices carry texture coordinates. */
public final class VertexFormat {
    final boolean textured;

    VertexFormat(boolean textured) {
        this.textured = textured;
    }
}
