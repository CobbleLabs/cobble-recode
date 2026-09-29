package cobble.core;

public record Vec2(float x, float y) {
    public Vec2 add(Vec2 o) {
        return new Vec2(x + o.x, y + o.y);
    }

    public Vec2 sub(Vec2 o) {
        return new Vec2(x - o.x, y - o.y);
    }

    public Vec2 mul(float s) {
        return new Vec2(x * s, y * s);
    }

    public float length() {
        return (float) Math.sqrt(x * x + y * y);
    }
}
