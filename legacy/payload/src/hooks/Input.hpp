#pragma once

#include <cstdint>
#include <mutex>
#include <vector>

namespace cold::hooks {

/* GLFW constants, spelled out rather than pulled in: the payload never links
 * GLFW, it borrows the one the game already loaded. */
inline constexpr int kRelease = 0;
inline constexpr int kPress = 1;
inline constexpr int kRepeat = 2;

inline constexpr int kKeyEscape = 256;
inline constexpr int kKeyEnter = 257;
inline constexpr int kKeyBackspace = 259;
inline constexpr int kKeyInsert = 260;
inline constexpr int kKeyDelete = 261;
inline constexpr int kKeyRightShift = 344;
inline constexpr int kKeyRightControl = 345;

inline constexpr int kMouseLeft = 0;
inline constexpr int kMouseRight = 1;
inline constexpr int kMouseMiddle = 2;

/* GLFW_KEY_SPACE through GLFW_KEY_LAST. Below space is only unknown and the
 * unused negative slot, and querying a key GLFW does not know about is an
 * error, not a false. */
inline constexpr int kFirstTrackedKey = 32;
inline constexpr int kLastTrackedKey = 348;

inline constexpr int kCursorMode = 0x00033001;
inline constexpr int kCursorNormal = 0x00034001;
inline constexpr int kCursorHidden = 0x00034002;
inline constexpr int kCursorDisabled = 0x00034003;

struct InputEvent {
    enum class Kind : std::uint8_t {
        Key,
        Char,
        MouseButton,
        MouseMove,
        Scroll,
    };

    Kind kind = Kind::Key;
    int code = 0;       // key code, mouse button, or code point
    int action = 0;     // press / release / repeat
    int modifiers = 0;
    double x = 0.0;     // cursor position or scroll delta
    double y = 0.0;
};

/* Chains onto Minecraft's own GLFW callbacks.
 *
 * Installing our callback returns the game's, so events can be inspected and
 * then forwarded, or swallowed while the menu owns the cursor. This needs no
 * trampoline and cannot desynchronise LWJGL's callback bookkeeping the way
 * detouring the C entry points would. */
class Input {
public:
    static Input& instance();

    bool attach(void* window);
    void detach();
    /* Re-installs the callbacks if the game replaced them, and refreshes the
     * held-button state. Cheap enough to call every frame. */
    void refresh();

    /* Drains everything queued since the last call. */
    std::vector<InputEvent> drain();

    void setCapturing(bool capturing);
    /* Sends a release to the game for everything currently held, so nothing is
     * left stuck down when the menu starts swallowing input. */
    void releaseHeldInputs();
    /* Hands the game a key event it never received. The verification suite uses
     * it to walk the player, which is the only way to exercise anything that
     * depends on movement without a human at the keyboard. */
    void sendKey(int key, bool down);
    bool capturing() const { return capturing_; }
    /* The cursor mode is changed through Minecraft's own Mouse so its internal
     * state stays consistent, which needs JNI and therefore the render thread.
     * This reports a pending change exactly once. */
    bool consumeCursorChange();

    /* Holds a mouse button down without one being down. GLFW is re-polled
     * every frame, so a synthetic press cannot live in the same field as the
     * real one; the verification suite drives modules that trigger on a held
     * button and there is no other way to reach them without a hand on the
     * mouse. */
    void setSyntheticButton(int button, bool down);

    bool attackHeld() const { return attackHeld_ || syntheticAttack_; }
    bool useHeld() const { return useHeld_ || syntheticUse_; }
    /* Live state straight from GLFW, so a bind works whether or not the client
     * happened to see the press that started it. */
    bool keyHeld(int key) const;
    /* A bind is a mouse button at eight and below and a key above it, which is
     * the rule the macros were written against. Mouse buttons go through the
     * held-state pair so a scripted run can press them. */
    bool bindHeld(int code) const;
    double cursorX() const { return cursorX_; }
    double cursorY() const { return cursorY_; }

    void* window() const { return window_; }

    /* Win32 input is WM_* on the game's window thread, which is the same
     * thread every frame hook runs on, so the WndProc chain fills the queue
     * and the tracked state directly instead of through GLFW-style
     * callbacks; these public helpers are that interface. */
    void push(const InputEvent& event) {
        std::lock_guard<std::mutex> guard(mutex_);
        if (queue_.size() < 256) {
            queue_.push_back(event);
        }
    }

    void trackButton(int button, bool down) {
        /* A click on a menu toggle is not the player attacking.
         *
         * The window procedure swallows the message while the menu owns the
         * cursor, so the release that ends a press recorded here never arrives
         * - and everything that reads attackHeld() acts on it in the meantime:
         * TriggerBot fires, AutoTool swaps, and Clutch writes it into the
         * game's own KeyBinding, where nothing is left to clear it and the
         * player's real clicks stop registering because the game already
         * believes the button is down. setCapturing zeroes the pair on the way
         * in for exactly this reason; tracking through it undid that on the
         * next click. The synthetic pair is separate, so the control channel's
         * `hold attack` still works. */
        if (capturing_) {
            return;
        }
        if (button == kMouseLeft) {
            attackHeld_ = down;
        } else if (button == kMouseRight) {
            useHeld_ = down;
        }
    }

    void trackCursor(double x, double y) {
        cursorX_ = x;
        cursorY_ = y;
    }

private:
    Input() = default;

    void* window_ = nullptr;
    bool attached_ = false;
    bool capturing_ = false;
    bool cursorDirty_ = false;

    bool attackHeld_ = false;
    bool useHeld_ = false;
    bool syntheticAttack_ = false;
    bool syntheticUse_ = false;
    double cursorX_ = 0.0;
    double cursorY_ = 0.0;

    std::mutex mutex_;
    std::vector<InputEvent> queue_;
};

inline Input& input() { return Input::instance(); }

}  // namespace cold::hooks
