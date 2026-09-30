#pragma once

#include <functional>
#include <memory>
#include <string>
#include <vector>

#include "core/Config.hpp"
#include "modules/Module.hpp"
#include "script/Script.hpp"

namespace cold::script {

class ScriptModule;
class HudElement;

enum class Notice : std::uint8_t {
    Info,
    Good,
    Warn,
    Bad,
};

/* Reads a whole file as bytes. Scripts are UTF-8 text and Lua does not care
 * beyond that, so nothing is transcoded. */
bool readTextFile(const std::wstring& path, std::string& out);

/* Absolute path of a file inside the scripts folder. */
std::wstring scriptPath(const std::string& file);

/* One line on the HUD's notification stack. Used by the manager itself and by
 * cold.notify. */
void notify(const std::string& text, Notice kind = Notice::Info, float seconds = 4.0f);

/* Owns every loaded script and the points the client dispatches into them.
 *
 * Two rules hold the whole thing together. Everything runs on the render
 * thread, inside the frame's JNI local frame, so a binding may touch the game
 * exactly as a C++ module does and nothing needs a lock. And nothing that
 * changes the script or module lists happens while either is being walked:
 * loading, unloading and reloading are requests, serviced at the top of the
 * next frame, which is what lets a script ask for its own reload from inside
 * one of its own callbacks. */
class ScriptManager {
public:
    static ScriptManager& instance();

    void initialise(const std::wstring& payloadDirectory);
    void shutdown();

    /* Frame pipeline. beginFrame services pending requests and raises the
     * world and entity events, so a script sees them before its tick. */
    void beginFrame(modules::Context& context);
    void endFrame();
    void tick();
    void render();
    void key(int code, bool down, int modifiers);
    void charTyped(unsigned int codepoint);
    void mouse(int button, bool down, double x, double y);
    void mouseMove(double x, double y);
    void scroll(double x, double y);

    /* The frame in flight, or null outside one. Every binding that touches the
     * game asks for this and refuses politely when it is not there. */
    modules::Context* context() const { return context_; }
    bool drawing() const { return drawing_ > 0; }
    void pushDrawing() { ++drawing_; }
    void popDrawing() {
        if (drawing_ > 0) --drawing_;
    }

    /* The renderer's clip stack is shared with the HUD and the menu, which are
     * drawn after the scripts are: one left open by a script would clip them
     * too, so the depth is tracked and unwound at the end of every callback. */
    int clipDepth() const { return clips_; }
    void noteClipPush() { ++clips_; }
    void noteClipPop();
    void unwindClips(int target);

    const std::vector<std::unique_ptr<Script>>& scripts() const { return scripts_; }
    Script* find(std::string_view file) const;
    bool enabled(const std::string& file) const;
    int loadedCount() const;

    void requestEnable(std::string file, bool enabled);
    /* An empty name reloads every script. */
    void requestReload(std::string file);
    void requestRescan();

    /* Per-script persistent storage, saved beside the payload. */
    config::Document& store() { return store_; }
    void saveStore();
    const std::wstring& directory() const { return directory_; }

    /* Hands a module built by a loading script to the module manager, which
     * takes ownership and applies whatever the config already held for it. */
    void attachModule(Script& script, std::unique_ptr<ScriptModule> module);

    /* HUD elements a script owns. They are drawn after the client's own HUD and
     * dragged with the menu open, so the drag has to be offered to them before
     * anything else outside the menu window gets the click. */
    void attachHudElement(Script& script, std::unique_ptr<HudElement> element);
    HudElement* findHudElement(std::string_view name) const;
    void removeHudElement(HudElement* element);
    const std::vector<std::unique_ptr<HudElement>>& hudElements() const { return hudElements_; }
    void renderHud(modules::Context& context, bool menuOpen);
    bool hudPress(float x, float y, float unit);
    void hudDrag(float x, float y, float unit);
    void hudRelease();
    /* True only while a script's chunk and its load handlers are running.
     * Modules may be declared then and at no other time: the module list is
     * walked every tick and every frame, and appending to it from inside one of
     * those walks would pull the ground out from under the loop. */
    bool loading() const { return loading_; }

private:
    struct Request {
        enum class Kind : std::uint8_t {
            Enable,
            Disable,
            Reload,
            ReloadAll,
            Rescan,
        };

        Kind kind = Kind::Rescan;
        std::string file;
    };

    ScriptManager() = default;

    void applyPending();
    void scan();
    bool startScript(Script& script);
    void stopScript(Script& script);
    void reloadScript(Script& script);
    void dispatch(Event event, const std::function<int(lua_State*)>& push = {});
    bool anyHandler(Event event) const;
    void trackWorld();
    void trackEntities(const game::FrameState& state);
    void trackPlayer(const game::FrameState& state);
    void trackModules();
    void trackEffects();
    void rebuildMenu();

    std::vector<std::unique_ptr<Script>> scripts_;
    std::vector<std::unique_ptr<HudElement>> hudElements_;
    HudElement* draggedElement_ = nullptr;
    float dragOffsetX_ = 0.0f;
    float dragOffsetY_ = 0.0f;
    std::vector<Request> pending_;
    config::Document store_;
    std::wstring directory_;
    std::wstring storePath_;

    modules::Context* context_ = nullptr;
    int drawing_ = 0;
    int clips_ = 0;
    bool initialised_ = false;
    bool loading_ = false;
    bool menuDirty_ = false;

    /* What the last frame looked like, so this one can say what changed. Each
     * of these is only kept up to date while something is actually listening
     * for the event it feeds. */
    struct SeenEntity {
        int id = 0;
        float health = 0.0f;
        bool living = false;
    };
    struct SeenEffect {
        std::string id;
        int amplifier = 0;
    };

    bool wasInWorld_ = false;
    bool wasScreenOpen_ = false;
    float lastHealth_ = -1.0f;
    int lastSlot_ = -1;
    std::vector<SeenEntity> seenEntities_;
    std::vector<SeenEntity> entityScratch_;
    std::vector<SeenEffect> seenEffects_;
    std::vector<const modules::Module*> toggledOn_;
};

inline ScriptManager& manager() { return ScriptManager::instance(); }

/* Marks the span in which the renderer's batch is open, which is the only time
 * a script may draw. */
struct DrawingScope {
    DrawingScope() : clips_(manager().clipDepth()) { manager().pushDrawing(); }
    ~DrawingScope() {
        manager().unwindClips(clips_);
        manager().popDrawing();
    }

    DrawingScope(const DrawingScope&) = delete;
    DrawingScope& operator=(const DrawingScope&) = delete;

private:
    int clips_;
};

}  // namespace cold::script
