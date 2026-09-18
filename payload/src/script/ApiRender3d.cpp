#include <cmath>
#include <string>

#include "render/Renderer.hpp"
#include "script/Api.hpp"
#include "script/ScriptManager.hpp"

namespace cold::script {
namespace {

/* World-space drawing, projected into the same batched 2D pass everything else
 * goes through.
 *
 * There is no separate 3D pass to hook: the client draws over the finished
 * frame, and the projection it uses is the exact view and projection matrix
 * Minecraft rendered that frame with. So a line between two world points is a
 * line between two projected points, and it stays welded to the world for the
 * same reason the ESP does. What it cannot do is depth-test against the world,
 * which is why everything here draws through walls - a script that wants
 * occlusion has to ask the world itself with cold.world.raycast. */

render::Renderer& requireDrawing(lua_State* L) {
    if (!manager().drawing()) {
        luaL_error(L, "drawing is only allowed from a render callback");
    }
    return render::renderer();
}

const game::Projection& requireProjection(lua_State* L) {
    modules::Context& context = requireContext(L);
    if (context.projection == nullptr || !context.projection->valid()) {
        luaL_error(L, "the frame has no camera to project with");
    }
    return *context.projection;
}

bool project(const game::Projection& projection, const Vec3& point, float& x, float& y) {
    game::ScreenPoint screen;
    if (!projection.project(point, screen)) {
        return false;
    }
    x = screen.x;
    y = screen.y;
    return true;
}

void drawSegment(render::Renderer& gfx, const game::Projection& projection, const Vec3& from,
                 const Vec3& to, float thickness, const Color& colour) {
    float x0 = 0.0f;
    float y0 = 0.0f;
    float x1 = 0.0f;
    float y1 = 0.0f;
    if (project(projection, from, x0, y0) && project(projection, to, x1, y1)) {
        gfx.line(x0, y0, x1, y1, thickness, colour);
    }
}

/* The eight corners of a world box, in the order the edge and face tables
 * below index them. */
void corners(const Vec3& minimum, const Vec3& maximum, Vec3 out[8]) {
    out[0] = {minimum.x, minimum.y, minimum.z};
    out[1] = {maximum.x, minimum.y, minimum.z};
    out[2] = {maximum.x, minimum.y, maximum.z};
    out[3] = {minimum.x, minimum.y, maximum.z};
    out[4] = {minimum.x, maximum.y, minimum.z};
    out[5] = {maximum.x, maximum.y, minimum.z};
    out[6] = {maximum.x, maximum.y, maximum.z};
    out[7] = {minimum.x, maximum.y, maximum.z};
}

constexpr int kEdges[12][2] = {{0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6},
                               {6, 7}, {7, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
constexpr int kFaces[6][4] = {{0, 1, 2, 3}, {4, 5, 6, 7}, {0, 1, 5, 4},
                              {2, 3, 7, 6}, {1, 2, 6, 5}, {3, 0, 4, 7}};

int readBox(lua_State* L, int index, Vec3& minimum, Vec3& maximum) {
    const int next = readVec3(L, index, minimum);
    const int after = readVec3(L, next, maximum);
    if (minimum.x > maximum.x) std::swap(minimum.x, maximum.x);
    if (minimum.y > maximum.y) std::swap(minimum.y, maximum.y);
    if (minimum.z > maximum.z) std::swap(minimum.z, maximum.z);
    return after;
}

int render3dLine(lua_State* L) {
    render::Renderer& gfx = requireDrawing(L);
    const game::Projection& projection = requireProjection(L);
    Vec3 from;
    Vec3 to;
    int next = readVec3(L, 1, from);
    next = readVec3(L, next, to);
    const Color colour = checkColour(L, next);
    drawSegment(gfx, projection, from, to, optFloat(L, next + 1, 1.5f), colour);
    return 0;
}

int render3dBox(lua_State* L) {
    render::Renderer& gfx = requireDrawing(L);
    const game::Projection& projection = requireProjection(L);
    Vec3 minimum;
    Vec3 maximum;
    const int next = readBox(L, 1, minimum, maximum);
    const Color colour = checkColour(L, next);
    const float thickness = optFloat(L, next + 1, 1.5f);

    Vec3 points[8];
    corners(minimum, maximum, points);
    for (const auto& edge : kEdges) {
        drawSegment(gfx, projection, points[edge[0]], points[edge[1]], thickness, colour);
    }
    return 0;
}

int render3dFilledBox(lua_State* L) {
    render::Renderer& gfx = requireDrawing(L);
    const game::Projection& projection = requireProjection(L);
    Vec3 minimum;
    Vec3 maximum;
    const int next = readBox(L, 1, minimum, maximum);
    const Color colour = checkColour(L, next);

    Vec3 points[8];
    corners(minimum, maximum, points);
    float screenX[8];
    float screenY[8];
    bool visible[8];
    for (int index = 0; index < 8; ++index) {
        visible[index] = project(projection, points[index], screenX[index], screenY[index]);
    }
    for (const auto& face : kFaces) {
        if (!visible[face[0]] || !visible[face[1]] || !visible[face[2]] || !visible[face[3]]) {
            continue;
        }
        const float xs[4] = {screenX[face[0]], screenX[face[1]], screenX[face[2]],
                             screenX[face[3]]};
        const float ys[4] = {screenY[face[0]], screenY[face[1]], screenY[face[2]],
                             screenY[face[3]]};
        gfx.face(xs, ys, colour);
    }
    return 0;
}

/* A line from the bottom of the screen to a point in the world, which is what
 * every tracer is. */
int render3dTracer(lua_State* L) {
    render::Renderer& gfx = requireDrawing(L);
    const game::Projection& projection = requireProjection(L);
    const game::FrameState& state = requireState(L);
    Vec3 point;
    const int next = readVec3(L, 1, point);
    const Color colour = checkColour(L, next);
    const float thickness = optFloat(L, next + 1, 1.5f);

    float x = 0.0f;
    float y = 0.0f;
    if (!project(projection, point, x, y)) {
        return 0;
    }
    const float fromX = static_cast<float>(state.windowWidth) * 0.5f;
    const float fromY = static_cast<float>(state.windowHeight);
    gfx.line(fromX, fromY, x, y, thickness, colour);
    return 0;
}

int render3dMarker(lua_State* L) {
    render::Renderer& gfx = requireDrawing(L);
    const game::Projection& projection = requireProjection(L);
    Vec3 point;
    const int next = readVec3(L, 1, point);
    const float size = optFloat(L, next, 4.0f);
    const Color colour = optColour(L, next + 1, Color::rgb(0xFFFFFF));

    float x = 0.0f;
    float y = 0.0f;
    if (!project(projection, point, x, y)) {
        return 0;
    }
    gfx.rect(Rect{x - size * 0.5f, y - size * 0.5f, size, size}, colour, size * 0.5f);
    return 0;
}

/* A horizontal ring on the ground, the shape every "circle round the target"
 * overlay is built from. */
int render3dCircle(lua_State* L) {
    render::Renderer& gfx = requireDrawing(L);
    const game::Projection& projection = requireProjection(L);
    Vec3 centre;
    const int next = readVec3(L, 1, centre);
    const double radius = luaL_optnumber(L, next, 1.0);
    const Color colour = optColour(L, next + 1, Color::rgb(0xFFFFFF));
    const int segments = clamp(static_cast<int>(luaL_optinteger(L, next + 2, 32)), 3, 128);
    const float thickness = optFloat(L, next + 3, 1.5f);

    Vec3 previous{centre.x + radius, centre.y, centre.z};
    for (int step = 1; step <= segments; ++step) {
        const double angle = 6.283185307179586 * static_cast<double>(step) / segments;
        const Vec3 point{centre.x + std::cos(angle) * radius, centre.y,
                         centre.z + std::sin(angle) * radius};
        drawSegment(gfx, projection, previous, point, thickness, colour);
        previous = point;
    }
    return 0;
}

int render3dText(lua_State* L) {
    render::Renderer& gfx = requireDrawing(L);
    const game::Projection& projection = requireProjection(L);
    Vec3 point;
    const int next = readVec3(L, 1, point);
    std::size_t length = 0;
    const char* text = luaL_checklstring(L, next, &length);
    const float size = optFloat(L, next + 1, 10.0f);
    const Color colour = optColour(L, next + 2, Color::rgb(0xFFFFFF));
    const bool shadow = optBoolean(L, next + 3, true);

    float x = 0.0f;
    float y = 0.0f;
    if (!project(projection, point, x, y)) {
        lua_pushboolean(L, 0);
        return 1;
    }
    const std::string_view value(text, length);
    if (shadow) {
        const float offset = std::max(1.0f, size * 0.09f);
        gfx.textCap(render::FontRole::Medium, value, x + offset, y + offset, size,
                    Color::rgb(0x000000, 0.55f * colour.a), render::Align::Center);
    }
    gfx.textCap(render::FontRole::Medium, value, x, y, size, colour, render::Align::Center);
    lua_pushboolean(L, 1);
    return 1;
}

/* Whether a point is in front of the camera at all, which is the question a
 * script asks before it does any work for that point. */
int render3dVisible(lua_State* L) {
    const game::Projection& projection = requireProjection(L);
    Vec3 point;
    readVec3(L, 1, point);
    game::ScreenPoint screen;
    lua_pushboolean(L, projection.project(point, screen) && screen.visible ? 1 : 0);
    return 1;
}

const luaL_Reg kFunctions[] = {
    {"line", render3dLine},       {"box", render3dBox},     {"filledBox", render3dFilledBox},
    {"tracer", render3dTracer},   {"marker", render3dMarker}, {"circle", render3dCircle},
    {"text", render3dText},       {"visible", render3dVisible}, {nullptr, nullptr},
};

}  // namespace

void installRender3d(lua_State* L) {
    lua_newtable(L);
    luaL_setfuncs(L, kFunctions, 0);
    lua_setfield(L, -2, "render3d");
}

}  // namespace cold::script
