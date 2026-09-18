#pragma once

#include <unordered_map>

#include "modules/Module.hpp"

namespace cold::modules {

/* Entity highlighting, drawn entirely as signed-distance fields in screen
 * space.
 *
 * Nothing is added to Minecraft's world render: the module projects each hitbox
 * with the frame's own view-projection matrix and evaluates the outline, halo
 * and health meter per pixel in the overlay shader. That keeps the game's
 * geometry, buffers and render passes untouched, so enabling it costs one extra
 * batched draw rather than a second pass over the entity list.
 *
 * The 3D box, the tracers and the off-screen arrows are the Raven-b `esp`
 * script's, and they work the same way - the corners of the hitbox and the ends
 * of a tracer are world points put through the same projection, so a line drawn
 * between two of them lands exactly where a line drawn in the world would. */
class Esp : public Module {
public:
    Esp();

    std::string suffix() const override;
    void onRender(Context& context) override;

private:
    /* Cached per entity so a target that despawns or leaves range can fade out
     * from where it was, instead of vanishing between two frames. */
    struct Entry {
        float fade = 0.0f;
        Rect bounds;
        float health = 0.0f;
        float maxHealth = 0.0f;
        double distance = 0.0;
        bool isPlayer = false;
        bool seen = false;
        std::string name;
        /* Kept so the 3D pass and the arrow can be drawn from the cache along
         * with everything else, including on the frames an entity is fading
         * out and no longer in the entity list. */
        Vec3 boxMin;
        Vec3 boxMax;
    };

    Color colourFor(const Entry& entry) const;
    void drawBox(const Rect& bounds, const Color& colour, float fade);
    void drawCorners(const Rect& bounds, const Color& colour, float fade);
    /* Twelve edges of the hitbox, and optionally its six faces, projected
     * corner by corner. Returns false when any corner is behind the camera,
     * where a screen-space line between two projected points is meaningless. */
    void drawBox3d(Context& context, const Entry& entry, const Color& colour, float fade);
    void drawTracer(Context& context, const Entry& entry, float fade);
    void drawArrow(Context& context, const Entry& entry, float fade);

    Setting* mode_;
    Setting* players_;
    Setting* mobs_;
    Setting* invisibles_;
    Setting* range_;
    Setting* colourMode_;
    Setting* glowStrength_;
    Setting* fill_;
    Setting* thickness_;
    Setting* healthBar_;
    Setting* nameLabel_;
    Setting* distanceLabel_;
    Setting* smoothing_;

    Setting* box3d_;
    Setting* fill3d_;
    Setting* thickness3d_;

    Setting* tracers_;
    Setting* tracerThickness_;
    Setting* tracerFrom_;

    Setting* arrows_;
    Setting* arrowStyle_;
    Setting* arrowRadius_;
    Setting* arrowSize_;
    Setting* arrowOutline_;
    Setting* arrowFade_;

    std::unordered_map<int, Entry> entries_;
};

}  // namespace cold::modules
