package cobble.v1_21.compat;

import cobble.core.Color;
import cobble.core.Rect;
import cobble.render.FontRole;
import cobble.render.Renderer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 1.8.9's fixed-function GL, as the legacy drawing code uses it, on 1.21.11's
 * core context, which has none of it. The calls keep the matrix stacks and
 * the current colour the way the driver would, and every primitive is
 * transformed to framebuffer pixels when it ends and kept until the overlay
 * draws its frame, where it goes out through the client's own renderer. There
 * is no depth test: the legacy code draws its overlays with it off anyway.
 *
 * <p>The world is drawn relative to the camera, so a world pass starts from
 * the camera's rotation and the game's projection; a GUI pass starts from the
 * orthographic projection over scaled units that 1.8 set for its HUD.
 */
final class GlEmu {
    static final int MODELVIEW = 0x1700;
    static final int PROJECTION = 0x1701;

    static final int LINES = 1;
    static final int LINE_LOOP = 2;
    static final int LINE_STRIP = 3;
    static final int TRIANGLES = 4;
    static final int TRIANGLE_STRIP = 5;
    static final int TRIANGLE_FAN = 6;
    static final int QUADS = 7;
    static final int QUAD_STRIP = 8;
    static final int POLYGON = 9;

    static final int TEXTURE_2D = 0x0DE1;
    static final int LINE_WIDTH = 0x0B21;
    static final int CURRENT_COLOR = 0x0B00;
    static final int MODELVIEW_MATRIX = 0x0BA6;
    static final int PROJECTION_MATRIX = 0x0BA7;
    static final int VIEWPORT = 0x0BA2;
    static final int TEXTURE_BINDING_2D = 0x8069;

    /* The MC font's line height and its glyphs' height above the baseline, in its own units. */
    static final float FONT_HEIGHT = 9.0f;
    private static final float FONT_SIZE = 8.0f;
    private static final FontRole FONT = FontRole.MEDIUM;
    private static final int MAX_COMMANDS = 1 << 16;
    private static final float NEAR_W = 0.01f;
    /* What 1.8's section-sign colour codes 0 to f stand for. */
    private static final int[] CODE_COLORS = {0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00,
        0xAAAAAA, 0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};

    private record Command(float[] xs, float[] ys, Color color, float thickness, String text, float size,
                           int texture, Rect uv, float radius) {}

    private static final Deque<float[]> modelview = new ArrayDeque<>();
    private static final Deque<float[]> projection = new ArrayDeque<>();
    private static final Set<Integer> enabled = new HashSet<>();
    private static final Deque<Object[]> attributes = new ArrayDeque<>();
    private static final List<Command> pending = new ArrayList<>();
    private static final Map<Integer, Integer> boundTextures = new HashMap<>();

    private static float[] worldViewProjection = identity();
    private static float[] currentModelview = identity();
    private static float[] currentProjection = identity();
    private static int matrixMode = MODELVIEW;
    private static float red = 1.0f;
    private static float green = 1.0f;
    private static float blue = 1.0f;
    private static float alpha = 1.0f;
    private static float lineWidth = 1.0f;
    private static int activeTexture;
    private static int width = 1;
    private static int height = 1;

    private static int mode = -1;
    private static final List<double[]> vertices = new ArrayList<>();

    private GlEmu() {}

    static float[] identity() {
        return new float[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    }

    private static float[] multiply(float[] a, float[] b) {
        float[] out = new float[16];
        for (int column = 0; column < 4; ++column) {
            for (int row = 0; row < 4; ++row) {
                float sum = 0.0f;
                for (int k = 0; k < 4; ++k) {
                    sum += a[k * 4 + row] * b[column * 4 + k];
                }
                out[column * 4 + row] = sum;
            }
        }
        return out;
    }

    /* ------------------------------------------------------------ passes -- */

    static void beginWorld(float[] view, float[] proj, int framebufferWidth, int framebufferHeight) {
        reset(framebufferWidth, framebufferHeight);
        currentModelview = view.clone();
        currentProjection = proj.clone();
        worldViewProjection = multiply(proj, view);
        ActiveRenderInfo.set(view, proj);
    }

    /** The last world pass's camera, for tests against the world outside any pass. */
    static float[] worldViewProjection() {
        return worldViewProjection;
    }

    static void beginGui(float scaledWidth, float scaledHeight, int framebufferWidth, int framebufferHeight) {
        reset(framebufferWidth, framebufferHeight);
        currentProjection = ortho(0, scaledWidth, scaledHeight, 0, 1000, 3000);
        currentModelview = identity();
        translate(0, 0, -2000);
    }

    private static void reset(int framebufferWidth, int framebufferHeight) {
        width = Math.max(1, framebufferWidth);
        height = Math.max(1, framebufferHeight);
        modelview.clear();
        projection.clear();
        attributes.clear();
        matrixMode = MODELVIEW;
        mode = -1;
        vertices.clear();
        red = green = blue = alpha = 1.0f;
        lineWidth = 1.0f;
    }

    /** Draws and forgets everything recorded since the last frame; the renderer's frame has to be open. */
    static void flush(Renderer gfx) {
        for (Command command : pending) {
            if (command.radius > 0.0f) {
                gfx.rect(new Rect(command.xs[0], command.ys[0], command.xs[1] - command.xs[0],
                        command.ys[1] - command.ys[0]), command.color, command.radius);
            } else if (command.text != null) {
                if (gfx.fonts().face(FONT).ready()) {
                    gfx.text(FONT, command.text, command.xs[0], command.ys[0], command.size, command.color);
                }
            } else if (command.texture != 0) {
                gfx.imageFace(command.xs, command.ys, command.uv, command.texture, command.color);
            } else if (command.xs.length == 2) {
                gfx.line(command.xs[0], command.ys[0], command.xs[1], command.ys[1], command.thickness, command.color);
            } else {
                gfx.face(command.xs, command.ys, command.color);
            }
        }
        pending.clear();
    }

    private static void emit(Command command) {
        if (pending.size() < MAX_COMMANDS) {
            pending.add(command);
        }
    }

    /* ------------------------------------------------------------ matrices -- */

    private static float[] current() {
        return matrixMode == PROJECTION ? currentProjection : currentModelview;
    }

    private static void setCurrent(float[] matrix) {
        if (matrixMode == PROJECTION) {
            currentProjection = matrix;
        } else {
            currentModelview = matrix;
        }
    }

    static void matrixMode(int mode) {
        matrixMode = mode == PROJECTION ? PROJECTION : MODELVIEW;
    }

    static void pushMatrix() {
        (matrixMode == PROJECTION ? projection : modelview).push(current().clone());
    }

    static void popMatrix() {
        Deque<float[]> stack = matrixMode == PROJECTION ? projection : modelview;
        if (!stack.isEmpty()) {
            setCurrent(stack.pop());
        }
    }

    static void loadIdentity() {
        setCurrent(identity());
    }

    static void loadMatrix(float[] matrix) {
        setCurrent(matrix.clone());
    }

    static void multMatrix(float[] matrix) {
        setCurrent(multiply(current(), matrix));
    }

    static void translate(double x, double y, double z) {
        float[] matrix = identity();
        matrix[12] = (float) x;
        matrix[13] = (float) y;
        matrix[14] = (float) z;
        multMatrix(matrix);
    }

    static void scale(double x, double y, double z) {
        float[] matrix = identity();
        matrix[0] = (float) x;
        matrix[5] = (float) y;
        matrix[10] = (float) z;
        multMatrix(matrix);
    }

    static void rotate(double angle, double x, double y, double z) {
        double length = Math.sqrt(x * x + y * y + z * z);
        if (length < 1.0e-9) {
            return;
        }
        x /= length;
        y /= length;
        z /= length;
        double radians = Math.toRadians(angle);
        float c = (float) Math.cos(radians);
        float s = (float) Math.sin(radians);
        float t = 1.0f - c;
        float fx = (float) x;
        float fy = (float) y;
        float fz = (float) z;
        multMatrix(new float[] {
            t * fx * fx + c, t * fx * fy + s * fz, t * fx * fz - s * fy, 0,
            t * fx * fy - s * fz, t * fy * fy + c, t * fy * fz + s * fx, 0,
            t * fx * fz + s * fy, t * fy * fz - s * fx, t * fz * fz + c, 0,
            0, 0, 0, 1
        });
    }

    static float[] ortho(double left, double right, double bottom, double top, double near, double far) {
        float[] matrix = identity();
        matrix[0] = (float) (2.0 / (right - left));
        matrix[5] = (float) (2.0 / (top - bottom));
        matrix[10] = (float) (-2.0 / (far - near));
        matrix[12] = (float) (-(right + left) / (right - left));
        matrix[13] = (float) (-(top + bottom) / (top - bottom));
        matrix[14] = (float) (-(far + near) / (far - near));
        return matrix;
    }

    static float[] modelview() {
        return currentModelview.clone();
    }

    static float[] projection() {
        return currentProjection.clone();
    }

    static int[] viewport() {
        return new int[] {0, 0, width, height};
    }

    /* --------------------------------------------------------------- state -- */

    static void color(float r, float g, float b, float a) {
        red = r;
        green = g;
        blue = b;
        alpha = a;
    }

    static float[] color() {
        return new float[] {red, green, blue, alpha};
    }

    static void lineWidth(float value) {
        lineWidth = value;
    }

    static float lineWidth() {
        return lineWidth;
    }

    static void enable(int capability) {
        enabled.add(capability);
    }

    static void disable(int capability) {
        enabled.remove(capability);
    }

    static boolean isEnabled(int capability) {
        return enabled.contains(capability);
    }

    static void activeTexture(int unit) {
        activeTexture = unit;
    }

    static void bindTexture(int texture) {
        boundTextures.put(activeTexture, texture);
    }

    static int boundTexture() {
        return boundTextures.getOrDefault(activeTexture, 0);
    }

    static void pushAttributes() {
        attributes.push(new Object[] {red, green, blue, alpha, lineWidth, new HashSet<>(enabled)});
    }

    @SuppressWarnings("unchecked")
    static void popAttributes() {
        if (attributes.isEmpty()) {
            return;
        }
        Object[] saved = attributes.pop();
        color((float) saved[0], (float) saved[1], (float) saved[2], (float) saved[3]);
        lineWidth = (float) saved[4];
        enabled.clear();
        enabled.addAll((Set<Integer>) saved[5]);
    }

    /* ---------------------------------------------------------- primitives -- */

    static void begin(int primitive) {
        mode = primitive;
        vertices.clear();
    }

    /** A vertex with its own colour, or the current one when {@code rgba} is null, and texture coordinates. */
    static void vertex(double x, double y, double z, float[] rgba, double u, double v) {
        if (mode < 0) {
            return;
        }
        float[] colour = rgba != null ? rgba : color();
        vertices.add(new double[] {x, y, z, colour[0], colour[1], colour[2], colour[3], u, v});
    }

    static void end() {
        if (mode < 0) {
            return;
        }
        float[] mvp = multiply(currentProjection, currentModelview);
        int count = vertices.size();
        switch (mode) {
            case LINES -> {
                for (int index = 0; index + 1 < count; index += 2) {
                    line(mvp, vertices.get(index), vertices.get(index + 1));
                }
            }
            case LINE_STRIP, LINE_LOOP -> {
                for (int index = 0; index + 1 < count; ++index) {
                    line(mvp, vertices.get(index), vertices.get(index + 1));
                }
                if (mode == LINE_LOOP && count > 2) {
                    line(mvp, vertices.get(count - 1), vertices.get(0));
                }
            }
            case TRIANGLES -> {
                for (int index = 0; index + 2 < count; index += 3) {
                    fill(mvp, vertices.get(index), vertices.get(index + 1), vertices.get(index + 2),
                            vertices.get(index + 2));
                }
            }
            case TRIANGLE_STRIP -> {
                for (int index = 0; index + 2 < count; ++index) {
                    fill(mvp, vertices.get(index), vertices.get(index + 1), vertices.get(index + 2),
                            vertices.get(index + 2));
                }
            }
            case TRIANGLE_FAN, POLYGON -> {
                for (int index = 1; index + 1 < count; ++index) {
                    fill(mvp, vertices.get(0), vertices.get(index), vertices.get(index + 1), vertices.get(index + 1));
                }
            }
            case QUADS -> {
                for (int index = 0; index + 3 < count; index += 4) {
                    fill(mvp, vertices.get(index), vertices.get(index + 1), vertices.get(index + 2),
                            vertices.get(index + 3));
                }
            }
            case QUAD_STRIP -> {
                for (int index = 0; index + 3 < count; index += 2) {
                    fill(mvp, vertices.get(index), vertices.get(index + 1), vertices.get(index + 3),
                            vertices.get(index + 2));
                }
            }
            default -> {
            }
        }
        mode = -1;
        vertices.clear();
    }

    private static float[] clip(float[] mvp, double[] vertex) {
        float x = (float) vertex[0];
        float y = (float) vertex[1];
        float z = (float) vertex[2];
        float[] out = new float[4];
        for (int row = 0; row < 4; ++row) {
            out[row] = mvp[row] * x + mvp[4 + row] * y + mvp[8 + row] * z + mvp[12 + row];
        }
        return out;
    }

    private static float screenX(float[] clip) {
        return (clip[0] / clip[3] * 0.5f + 0.5f) * width;
    }

    private static float screenY(float[] clip) {
        return (0.5f - clip[1] / clip[3] * 0.5f) * height;
    }

    private static Color colour(double[] vertex) {
        return new Color((float) vertex[3], (float) vertex[4], (float) vertex[5], (float) vertex[6]);
    }

    /* A segment crossing the camera plane is cut where it does, so a line to something behind still points at it. */
    private static void line(float[] mvp, double[] from, double[] to) {
        float[] a = clip(mvp, from);
        float[] b = clip(mvp, to);
        if (a[3] < NEAR_W && b[3] < NEAR_W) {
            return;
        }
        if (a[3] < NEAR_W || b[3] < NEAR_W) {
            float[] behind = a[3] < NEAR_W ? a : b;
            float[] front = behind == a ? b : a;
            float t = (NEAR_W - front[3]) / (behind[3] - front[3]);
            for (int index = 0; index < 4; ++index) {
                behind[index] = front[index] + (behind[index] - front[index]) * t;
            }
        }
        emit(new Command(new float[] {screenX(a), screenX(b)}, new float[] {screenY(a), screenY(b)}, colour(from),
                Math.max(1.0f, lineWidth), null, 0.0f, 0, null, 0.0f));
    }

    private static void fill(float[] mvp, double[]... corners) {
        float[] xs = new float[4];
        float[] ys = new float[4];
        double minU = Double.MAX_VALUE;
        double minV = Double.MAX_VALUE;
        double maxU = -Double.MAX_VALUE;
        double maxV = -Double.MAX_VALUE;
        for (int index = 0; index < 4; ++index) {
            float[] point = clip(mvp, corners[index]);
            if (point[3] < NEAR_W) {
                return;
            }
            xs[index] = screenX(point);
            ys[index] = screenY(point);
            minU = Math.min(minU, corners[index][7]);
            minV = Math.min(minV, corners[index][8]);
            maxU = Math.max(maxU, corners[index][7]);
            maxV = Math.max(maxV, corners[index][8]);
        }
        int texture = isEnabled(TEXTURE_2D) ? boundTexture() : 0;
        Rect uv = new Rect((float) minU, (float) minV, (float) (maxU - minU), (float) (maxV - minV));
        emit(new Command(xs, ys, colour(corners[0]), 0.0f, null, 0.0f, texture,
                uv, 0.0f));
    }

    /** A screen-aligned rectangle with rounded corners at the current matrices; GUI drawing only rotates it rarely. */
    static void roundedRect(float x, float y, float rectWidth, float rectHeight, float radius, int argb) {
        float[] mvp = multiply(currentProjection, currentModelview);
        float[] from = clip(mvp, new double[] {x, y, 0});
        float[] to = clip(mvp, new double[] {x + rectWidth, y + rectHeight, 0});
        if (from[3] < NEAR_W || to[3] < NEAR_W) {
            return;
        }
        float left = Math.min(screenX(from), screenX(to));
        float top = Math.min(screenY(from), screenY(to));
        float right = Math.max(screenX(from), screenX(to));
        float bottom = Math.max(screenY(from), screenY(to));
        float pixelsPerUnit = rectWidth != 0.0f ? (right - left) / Math.abs(rectWidth) : 1.0f;
        Color color = new Color((argb >> 16 & 0xFF) / 255.0f, (argb >> 8 & 0xFF) / 255.0f, (argb & 0xFF) / 255.0f,
                (argb >>> 24 & 0xFF) / 255.0f);
        if (radius <= 0.0f) {
            emit(new Command(new float[] {left, right, right, left}, new float[] {top, top, bottom, bottom}, color,
                    0.0f, null, 0.0f, 0, null, 0.0f));
        } else {
            emit(new Command(new float[] {left, right}, new float[] {top, bottom}, color, 0.0f, null, 0.0f, 0, null,
                    radius * pixelsPerUnit));
        }
    }

    static int codeColor(char code) {
        int index = "0123456789abcdef".indexOf(Character.toLowerCase(code));
        return index >= 0 ? CODE_COLORS[index] : -1;
    }

    /* ---------------------------------------------------------------- text -- */

    /** Width in the MC font's units, measured with the overlay's font at the size it stands in for. */
    static float textWidth(String text) {
        Renderer gfx = Renderer.instance();
        if (!gfx.ready() || !gfx.fonts().face(FONT).ready()) {
            return text.length() * 6.0f;
        }
        return gfx.measure(FONT, text, FONT_SIZE);
    }

    /**
     * Places the text where the current matrices put its origin, at the size
     * one font unit has there; 1.8's section-sign codes pick its colours.
     */
    static float text(String text, float x, float y, int rgb, boolean shadow) {
        float[] mvp = multiply(currentProjection, currentModelview);
        float[] top = clip(mvp, new double[] {x, y, 0});
        float[] bottom = clip(mvp, new double[] {x, y + FONT_SIZE, 0});
        if (top[3] < NEAR_W || bottom[3] < NEAR_W) {
            return x;
        }
        float pixels = Math.abs(screenY(bottom) - screenY(top)) + Math.abs(screenX(bottom) - screenX(top));
        if (pixels < 1.0f) {
            return x;
        }
        float scale = pixels / FONT_SIZE;
        float alpha = (rgb >>> 24 & 0xFF) == 0 ? 1.0f : (rgb >>> 24 & 0xFF) / 255.0f;
        Color base = new Color((rgb >> 16 & 0xFF) / 255.0f, (rgb >> 8 & 0xFF) / 255.0f, (rgb & 0xFF) / 255.0f,
                alpha);
        float penX = screenX(top);
        float penY = screenY(top);
        Color colour = base;
        StringBuilder run = new StringBuilder();
        for (int index = 0; index < text.length(); ++index) {
            char c = text.charAt(index);
            if (c == '§' && index + 1 < text.length()) {
                penX = run(run, penX, penY, scale, colour, shadow);
                int code = "0123456789abcdef".indexOf(Character.toLowerCase(text.charAt(++index)));
                if (code >= 0) {
                    int value = CODE_COLORS[code];
                    colour = new Color((value >> 16 & 0xFF) / 255.0f, (value >> 8 & 0xFF) / 255.0f,
                            (value & 0xFF) / 255.0f, alpha);
                } else if (Character.toLowerCase(text.charAt(index)) == 'r') {
                    colour = base;
                }
            } else {
                run.append(c);
            }
        }
        penX = run(run, penX, penY, scale, colour, shadow);
        return x + (penX - screenX(top)) / scale;
    }

    private static float run(StringBuilder run, float x, float y, float scale, Color colour, boolean shadow) {
        if (run.length() == 0) {
            return x;
        }
        String part = run.toString();
        run.setLength(0);
        float size = FONT_SIZE * scale;
        if (shadow) {
            emit(new Command(new float[] {x + scale}, new float[] {y + scale},
                    new Color(colour.r() * 0.25f, colour.g() * 0.25f, colour.b() * 0.25f, colour.a()), 0.0f, part,
                    size, 0, null, 0.0f));
        }
        emit(new Command(new float[] {x}, new float[] {y}, colour, 0.0f, part, size, 0, null, 0.0f));
        return x + textWidth(part) * scale;
    }
}
