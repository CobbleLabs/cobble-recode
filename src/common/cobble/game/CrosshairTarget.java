package cobble.game;

import cobble.core.Vec3;

/**
 * What Minecraft's own crosshair pick says the player is pointing at. The
 * server reconstructs an interaction from the rotation it was sent, so acting
 * on anything else is acting on something the server will not agree with.
 */
public record CrosshairTarget(Kind kind, int x, int y, int z, Face face, Vec3 point,
                              Object entity, int entityId, boolean crystal, boolean slime) {
    public enum Kind { MISS, BLOCK, ENTITY }

    public static final CrosshairTarget MISS =
            new CrosshairTarget(Kind.MISS, 0, 0, 0, Face.UP, Vec3.ZERO, null, -1, false, false);
}
