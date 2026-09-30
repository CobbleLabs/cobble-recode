package cobble.module.combat;

import cobble.core.Color;
import cobble.event.AttackEntityEvent;
import cobble.event.DisconnectEvent;
import cobble.event.LoadWorldEvent;
import cobble.event.PacketReceiveEvent;
import cobble.event.RenderWorldLastEvent;
import cobble.event.Subscribe;
import cobble.event.TickStartEvent;
import cobble.module.Category;
import cobble.module.Context;
import cobble.module.Module;
import cobble.module.ModuleManager;
import cobble.module.Setting;
import cobble.module.Subcategory;
import cobble.module.player.Blink;
import cobble.ui.Client;
import cobble.util.ClientUtils;
import cobble.util.packet.PacketUtils;
import cobble.util.render.RenderUtils;
import cobble.util.render.RenderUtils$1;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.SplittableRandom;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S14PacketEntity;
import net.minecraft.network.play.server.S18PacketEntityTeleport;
import net.minecraft.util.MovingObjectPosition;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

/**
 * Cobble's Backtrack: entity movement is held back for a jittered hold time,
 * so the player you are fighting is drawn and hit where they were a moment
 * ago. A box marks where the server already has them.
 *
 * <p>With Whole Stream on, every entity's movement is delayed for as long as
 * the module is on. A delay that appears and vanishes with the crosshair is
 * the pattern that gives the whole thing away; only the rewind follows the
 * target.
 */
public class Backtrack extends Module {
    private static final int QUEUE_DRAIN = 1024;
    private static final int MAX_RELEASE_BUDGET = 128;
    /* Exponential catch-up per second: just behind the server position, high
     * enough to aim at, low enough that packet jitter does not make it chatter. */
    private static final double GHOST_FOLLOW = 18.0;
    private static final double HALF_WIDTH = 0.3;
    private static final double HEIGHT = 1.8;
    private static final float LINE_WIDTH = 1.35f;
    private static final float CORNER_LENGTH = 0.16f;
    private static final float[] FACE_SHADE = {0.72f, 0.86f, 0.52f, 0.62f, 0.44f, 0.94f};
    private static final float[] FACE_ALPHA = {0.075f, 0.055f, 0.060f, 0.045f, 0.050f, 0.065f};
    private static final int[][] FACES = {
        {0, 1, 2, 3}, {4, 7, 6, 5}, {0, 4, 5, 1}, {1, 5, 6, 2}, {2, 6, 7, 3}, {3, 7, 4, 0},
    };
    private static final int[][] EDGES = {
        {0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7},
    };
    private static final int[][] CORNERS = {
        {0, 1, 3}, {1, 0, 2}, {2, 1, 3}, {3, 0, 2}, {4, 5, 7}, {5, 4, 6}, {6, 5, 7}, {7, 4, 6},
    };

    private final Setting mode = addChoice("Mode", "Hold always, or only right after a hit",
            List.of("Always", "Only on Hit"), 0);
    private final Setting holdTime = addNumber("Hold Time (ms)", "How long movement is held back", 180, 20, 400, 0);
    private final Setting packetLimit = addNumber("Packet Limit", "Held target packets before everything is released",
            32, 2, 64, 0);
    private final Setting wholeStream = addBoolean("Whole Stream", "Delay every entity, not just the target", true);
    private final Setting minRange = addNumber("Min Range", "Closest a target may be", 0, 0, 6, 1);
    private final Setting maxRange = addNumber("Max Range", "Farthest a target may be", 6, 0, 6, 1);
    private final Setting onlyWhileAttacking = addBoolean("Only While Attacking",
            "Needs left held, AutoClicker on or AimAssist on a target", false);
    private final Setting colour = addColour("Color", "Box colour; its alpha is the box opacity",
            new Color(0.42f, 0.92f, 1.0f, 1.0f));

    private final Deque<Held> queue = new ArrayDeque<>();
    private final SplittableRandom random = new SplittableRandom();
    private volatile boolean streamHold;
    private volatile int targetId = -1;
    private EntityPlayer target;
    private int hitEntityId = -1;
    private long hitAt;

    /* Latency model, guarded by the queue. Each packet's delay drifts around
     * the hold time rather than being drawn fresh, as a real connection's does. */
    private boolean modelPrimed;
    private double latencyCurrent;
    private double latencyTarget;
    private long lastAdmit;
    private long lastDeadline;
    private int admitsSinceService;
    private double arrivalsPerService;

    private boolean ghostInitialized;
    private int ghostId = -1;
    private double ghostX;
    private double ghostY;
    private double ghostZ;
    private float ghostAlpha;
    private long lastFrame;

    private record Held(Packet<?> packet, int entityId, long releaseAt, boolean teleport, int x, int y, int z) {}

    public Backtrack() {
        super("Backtrack", "Holds back enemy movement so you hit where they were", Category.COMBAT, Subcategory.EXTREME);
    }

    @Override
    public String suffix() {
        return holdTime.intValue() + "ms";
    }

    @Override
    public void onEnable(Context context) {
        if (isBlinkConflict()) {
            ClientUtils.sendColoredMessage("&cBacktrack conflicts with Blink inbound. Disable Blink or use outbound-only.");
            setEnabled(false, Client.context());
            return;
        }
        disengage();
        ghostInitialized = false;
        ghostAlpha = 0.0f;
        lastFrame = 0L;
    }

    @Override
    public void onDisable(Context context) {
        streamHold = false;
        disengage();
        flushAll();
    }

    public boolean isLagging() {
        synchronized (queue) {
            return enabled() && !queue.isEmpty();
        }
    }

    @Subscribe(priority = Subscribe.Priority.HIGHEST)
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.cancelled()) {
            return;
        }
        Packet<?> packet = event.getPacket();
        int entityId;
        boolean teleport;
        int x;
        int y;
        int z;
        if (packet instanceof S14PacketEntity move) {
            entityId = move.entityId;
            teleport = false;
            x = move.func_149062_c();
            y = move.func_149061_d();
            z = move.func_149064_e();
        } else if (packet instanceof S18PacketEntityTeleport teleportPacket) {
            entityId = teleportPacket.getEntityId();
            teleport = true;
            x = teleportPacket.getX();
            y = teleportPacket.getY();
            z = teleportPacket.getZ();
        } else {
            return;
        }

        synchronized (queue) {
            /* Once an entity has anything queued, the rest of its movement has
             * to queue behind it or it would be applied out of order. */
            if (!streamHold && targetId != entityId && pendingFor(entityId) == 0) {
                return;
            }
            long now = System.currentTimeMillis();
            queue.addLast(new Held(packet, entityId, nextDeadline(now), teleport, x, y, z));
            ++admitsSinceService;
        }
        event.setCancelled(true);
    }

    @Subscribe
    public void onAttackEntity(AttackEntityEvent event) {
        if (mode.choice() == 1 && event.entityPlayer == mc.thePlayer && event.entity instanceof EntityPlayer) {
            hitEntityId = event.entity.getEntityId();
            hitAt = System.currentTimeMillis();
        }
    }

    @Subscribe
    public void onLoadWorld(LoadWorldEvent event) {
        discard();
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        discard();
    }

    @Subscribe(priority = Subscribe.Priority.LOWEST)
    public void onTickStart(TickStartEvent event) {
        if (!ClientUtils.isInWorld()) {
            streamHold = false;
            disengage();
            flushAll();
            return;
        }
        if (needsEmergencyDrain()) {
            streamHold = false;
            disengage();
            flushAll();
        }

        EntityPlayer candidate = engageable();
        if (wholeStream.bool()) {
            streamHold = true;
            engage(candidate);
        } else {
            streamHold = false;
            if (candidate == null) {
                disengage();
                flushAll();
                return;
            }
            if (candidate.getEntityId() != targetId) {
                disengage();
                flushAll();
            }
            engage(candidate);
        }

        releaseDue();
        if (needsEmergencyDrain()) {
            streamHold = false;
            disengage();
            flushAll();
        }
    }

    private void engage(EntityPlayer player) {
        target = player;
        targetId = player == null ? -1 : player.getEntityId();
    }

    private void disengage() {
        target = null;
        targetId = -1;
    }

    private EntityPlayer engageable() {
        if (Client.gui().visible() || mc.currentScreen != null) {
            return null;
        }
        if (onlyWhileAttacking.bool() && !attackSourceActive()) {
            return null;
        }
        MovingObjectPosition hit = mc.objectMouseOver;
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.ENTITY
                || !(hit.entityHit instanceof EntityPlayer player)) {
            return null;
        }
        if (mode.choice() == 1 && (hitEntityId != player.getEntityId()
                || System.currentTimeMillis() - hitAt > Math.max(holdTime.intValue(), 20))) {
            return null;
        }
        double distance = mc.thePlayer.getDistanceToEntity(player);
        float low = Math.min(minRange.number(), maxRange.number());
        float high = Math.max(minRange.number(), maxRange.number());
        return distance >= low && distance <= high ? player : null;
    }

    private static boolean attackSourceActive() {
        return mc.inGameHasFocus && Mouse.isButtonDown(0)
                || ModuleManager.get(AutoClicker.class).enabled()
                || ModuleManager.get(AimAssist.class).hasTarget();
    }

    private boolean needsEmergencyDrain() {
        synchronized (queue) {
            return queue.size() >= QUEUE_DRAIN || targetId != -1 && pendingFor(targetId) >= packetLimit.intValue();
        }
    }

    /** Caller holds the queue. */
    private int pendingFor(int entityId) {
        int count = 0;
        for (Held held : queue) {
            if (held.entityId == entityId) {
                ++count;
            }
        }
        return count;
    }

    /** Caller holds the queue. Deadlines never go backwards, so release stays in order. */
    private long nextDeadline(long now) {
        double base = Math.min(holdTime.number(), 1000.0);
        double band = base * 0.12;
        if (!modelPrimed) {
            latencyCurrent = base;
            latencyTarget = base;
            modelPrimed = true;
        } else {
            double elapsed = Math.max(0L, now - lastAdmit);
            if (random.nextDouble() < elapsed / 400.0) {
                latencyTarget = base + signedUnit() * band;
            }
            latencyCurrent += (latencyTarget - latencyCurrent) * (1.0 - Math.exp(-elapsed / 250.0));
        }
        lastAdmit = now;
        double delay = Math.max(base * 0.88, Math.min(base * 1.12, latencyCurrent + signedUnit() * band * 0.15));
        lastDeadline = Math.max(now + Math.round(delay), lastDeadline);
        return lastDeadline;
    }

    private double signedUnit() {
        return random.nextDouble() * 2.0 - 1.0;
    }

    /* Releasing about twice the recent arrival rate per tick drains a backlog
     * without dumping it in one burst. */
    private void releaseDue() {
        List<Packet<?>> due = new ArrayList<>();
        synchronized (queue) {
            arrivalsPerService += (admitsSinceService - arrivalsPerService) * 0.25;
            admitsSinceService = 0;
            int budget = Math.max(2, Math.min(MAX_RELEASE_BUDGET, (int) Math.ceil(arrivalsPerService * 2.0)));
            long now = System.currentTimeMillis();
            while (!queue.isEmpty() && due.size() < budget && queue.peekFirst().releaseAt <= now) {
                due.add(queue.pollFirst().packet);
            }
        }
        process(due);
    }

    private void flushAll() {
        List<Packet<?>> all;
        synchronized (queue) {
            all = new ArrayList<>(queue.size());
            for (Held held : queue) {
                all.add(held.packet);
            }
            queue.clear();
            modelPrimed = false;
            lastDeadline = 0L;
        }
        process(all);
    }

    /* A new world or connection makes the held movement meaningless;
     * replaying it would move entities that no longer exist. */
    private void discard() {
        streamHold = false;
        disengage();
        synchronized (queue) {
            queue.clear();
            modelPrimed = false;
            lastDeadline = 0L;
        }
    }

    private static void process(List<Packet<?>> packets) {
        for (Packet<?> packet : packets) {
            PacketUtils.processInboundPacket(packet);
        }
    }

    @Subscribe
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        long now = System.nanoTime();
        double frame = lastFrame == 0L ? 1.0 / 60.0 : (now - lastFrame) / 1.0e9;
        lastFrame = now;
        if (frame <= 0.0 || frame > 0.25) {
            frame = 1.0 / 60.0;
        }
        frame = Math.min(frame, 0.05);

        EntityPlayer player = target;
        boolean active = false;
        if (player != null) {
            int id = player.getEntityId();
            int x = player.serverPosX;
            int y = player.serverPosY;
            int z = player.serverPosZ;
            int pending = 0;
            synchronized (queue) {
                for (Held held : queue) {
                    if (held.entityId != id) {
                        continue;
                    }
                    ++pending;
                    if (held.teleport) {
                        x = held.x;
                        y = held.y;
                        z = held.z;
                    } else {
                        x += held.x;
                        y += held.y;
                        z += held.z;
                    }
                }
            }
            active = pending > 0;
            if (active) {
                followGhost(id, x / 32.0, y / 32.0, z / 32.0, frame);
            }
        }

        float alphaBlend = (float) (1.0 - Math.exp(-(active ? 14.0 : 10.0) * frame));
        ghostAlpha = Math.max(0.0f, Math.min(1.0f, ghostAlpha + ((active ? 1.0f : 0.0f) - ghostAlpha) * alphaBlend));
        Color colour = this.colour.colour();
        float alpha = ghostAlpha * Math.max(0.0f, Math.min(1.0f, colour.a()));
        if (ghostInitialized && alpha > 0.01f) {
            drawGhost(colour, alpha);
        }
    }

    private void followGhost(int id, double x, double y, double z, double frame) {
        double dx = x - ghostX;
        double dy = y - ghostY;
        double dz = z - ghostZ;
        boolean targetChanged = !ghostInitialized || ghostId != id;
        if (targetChanged || dx * dx + dy * dy + dz * dz > 36.0) {
            ghostX = x;
            ghostY = y;
            ghostZ = z;
            ghostId = id;
            ghostInitialized = true;
            if (targetChanged) {
                ghostAlpha = 0.0f;
            }
        } else {
            double blend = 1.0 - Math.exp(-GHOST_FOLLOW * frame);
            ghostX += dx * blend;
            ghostY += dy * blend;
            ghostZ += dz * blend;
        }
    }

    private void drawGhost(Color colour, float alpha) {
        RenderManager renderManager = mc.getRenderManager();
        double x = ghostX - renderManager.viewerPosX;
        double y = ghostY - renderManager.viewerPosY;
        double z = ghostZ - renderManager.viewerPosZ;
        double[][] corners = {
            {x - HALF_WIDTH, y, z - HALF_WIDTH}, {x + HALF_WIDTH, y, z - HALF_WIDTH},
            {x + HALF_WIDTH, y, z + HALF_WIDTH}, {x - HALF_WIDTH, y, z + HALF_WIDTH},
            {x - HALF_WIDTH, y + HEIGHT, z - HALF_WIDTH}, {x + HALF_WIDTH, y + HEIGHT, z - HALF_WIDTH},
            {x + HALF_WIDTH, y + HEIGHT, z + HALF_WIDTH}, {x - HALF_WIDTH, y + HEIGHT, z + HALF_WIDTH},
        };
        float r = colour.r();
        float g = colour.g();
        float b = colour.b();

        GlStateManager.pushMatrix();
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.disableCull();
        RenderUtils$1 lightmap = RenderUtils.uyB6();
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer buffer = tessellator.getWorldRenderer();
        try {
            buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
            for (int face = 0; face < FACES.length; ++face) {
                float shade = FACE_SHADE[face];
                for (int vertex : FACES[face]) {
                    double[] p = corners[vertex];
                    buffer.pos(p[0], p[1], p[2]).color(r * shade, g * shade, b * shade, alpha * FACE_ALPHA[face])
                            .endVertex();
                }
            }
            tessellator.draw();

            drawEdges(corners, lighten(r, 0.18f), lighten(g, 0.18f), lighten(b, 0.18f), alpha * 0.22f,
                    LINE_WIDTH * 3.70f);
            drawEdges(corners, 0.0f, 0.0f, 0.0f, alpha * 0.82f, LINE_WIDTH * 2.22f);
            drawEdges(corners, r, g, b, alpha, LINE_WIDTH);

            GL11.glLineWidth(LINE_WIDTH * 1.55f);
            buffer.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
            float cr = lighten(r, 0.52f);
            float cg = lighten(g, 0.52f);
            float cb = lighten(b, 0.52f);
            for (int[] corner : CORNERS) {
                double[] p = corners[corner[0]];
                for (int i = 1; i < 3; ++i) {
                    double[] q = corners[corner[i]];
                    buffer.pos(p[0], p[1], p[2]).color(cr, cg, cb, alpha).endVertex();
                    buffer.pos(p[0] + (q[0] - p[0]) * CORNER_LENGTH, p[1] + (q[1] - p[1]) * CORNER_LENGTH,
                            p[2] + (q[2] - p[2]) * CORNER_LENGTH).color(cr, cg, cb, alpha).endVertex();
                }
            }
            tessellator.draw();
        } finally {
            RenderUtils.restoreLightmapState(lightmap);
            GL11.glLineWidth(1.0f);
            GlStateManager.enableCull();
            GlStateManager.enableDepth();
            GlStateManager.depthMask(true);
            GlStateManager.disableBlend();
            GlStateManager.enableTexture2D();
            GlStateManager.popMatrix();
        }
    }

    private static void drawEdges(double[][] corners, float r, float g, float b, float a, float width) {
        GL11.glLineWidth(width);
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer buffer = tessellator.getWorldRenderer();
        buffer.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        for (int[] edge : EDGES) {
            for (int vertex : edge) {
                double[] p = corners[vertex];
                buffer.pos(p[0], p[1], p[2]).color(r, g, b, a).endVertex();
            }
        }
        tessellator.draw();
    }

    private static float lighten(float channel, float amount) {
        return channel + (1.0f - channel) * amount;
    }

    private static boolean isBlinkConflict() {
        Blink blink = ModuleManager.get(Blink.class);
        return blink.enabled() && blink.isInboundMode();
    }
}
