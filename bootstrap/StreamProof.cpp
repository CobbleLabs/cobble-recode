#include "StreamProof.hpp"

#include <windows.h>

#include <GL/gl.h>

#include <algorithm>
#include <atomic>
#include <cstdarg>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <utility>

namespace streamproof {
namespace {

constexpr DWORD kExcludeFromCapture = 0x11;  // WDA_EXCLUDEFROMCAPTURE
/* Older builds take the flag but treat it as WDA_MONITOR, which records a black box. */
constexpr DWORD kExcludeFromCaptureBuild = 19041;
/* Without a fresh frame the window hides, so a stalled game never leaves a frozen layer over it. */
constexpr ULONGLONG kFrameTimeoutMillis = 250;
constexpr wchar_t kWindowClass[] = L"CobbleLayer";

constexpr GLenum kFramebuffer = 0x8D40;
constexpr GLenum kReadFramebuffer = 0x8CA8;
constexpr GLenum kDrawFramebuffer = 0x8CA9;
constexpr GLenum kReadFramebufferBinding = 0x8CAA;
constexpr GLenum kDrawFramebufferBinding = 0x8CA6;
constexpr GLenum kColorAttachment0 = 0x8CE0;
constexpr GLenum kDepthAttachment = 0x8D00;
constexpr GLenum kRenderbuffer = 0x8D41;
constexpr GLenum kAttachmentObjectType = 0x8CD0;
constexpr GLenum kAttachmentObjectName = 0x8CD1;
constexpr GLenum kFramebufferComplete = 0x8CD5;
constexpr GLenum kPixelPackBuffer = 0x88EB;
constexpr GLenum kPixelPackBufferBinding = 0x88ED;
constexpr GLenum kPixelUnpackBuffer = 0x88EC;
constexpr GLenum kPixelUnpackBufferBinding = 0x88EF;
constexpr GLenum kStreamRead = 0x88E1;
constexpr GLenum kReadOnly = 0x88B8;
constexpr GLenum kBgra = 0x80E1;
constexpr GLenum kRgba8 = 0x8058;

#define LAYER_GL_FUNCTIONS(X)                                                 \
    X(void, GenFramebuffers, (GLsizei, GLuint*))                              \
    X(void, DeleteFramebuffers, (GLsizei, const GLuint*))                     \
    X(GLboolean, IsFramebuffer, (GLuint))                                     \
    X(void, BindFramebuffer, (GLenum, GLuint))                                \
    X(void, FramebufferTexture2D, (GLenum, GLenum, GLenum, GLuint, GLint))    \
    X(void, FramebufferRenderbuffer, (GLenum, GLenum, GLenum, GLuint))        \
    X(void, GetFramebufferAttachmentParameteriv, (GLenum, GLenum, GLenum, GLint*)) \
    X(GLenum, CheckFramebufferStatus, (GLenum))                               \
    X(void, GenBuffers, (GLsizei, GLuint*))                                   \
    X(void, DeleteBuffers, (GLsizei, const GLuint*))                          \
    X(void, BindBuffer, (GLenum, GLuint))                                     \
    X(void, BufferData, (GLenum, std::ptrdiff_t, const void*, GLenum))        \
    X(void*, MapBuffer, (GLenum, GLenum))                                     \
    X(GLboolean, UnmapBuffer, (GLenum))

#define LAYER_DECLARE(ret, name, args) ret(APIENTRY* gl##name) args = nullptr;
LAYER_GL_FUNCTIONS(LAYER_DECLARE)
#undef LAYER_DECLARE

Logger g_logger = nullptr;

void note(const char* format, ...) {
    if (g_logger == nullptr) {
        return;
    }
    char text[512];
    va_list args;
    va_start(args, format);
    std::vsnprintf(text, sizeof(text), format, args);
    va_end(args);
    g_logger(text);
}

bool loadGl() {
    /* Some drivers answer 1, 2, 3 or -1 for a missing entry point, not just null. */
#define LAYER_LOAD(ret, name, args)                                            \
    {                                                                          \
        const PROC proc = wglGetProcAddress("gl" #name);                       \
        const auto raw = reinterpret_cast<std::intptr_t>(proc);                \
        if (raw == 0 || raw == 1 || raw == 2 || raw == 3 || raw == -1) {       \
            note("gl" #name " is missing");                                     \
            return false;                                                      \
        }                                                                      \
        gl##name = reinterpret_cast<decltype(gl##name)>(proc);                 \
    }
    LAYER_GL_FUNCTIONS(LAYER_LOAD)
#undef LAYER_LOAD
    return true;
}
#undef LAYER_GL_FUNCTIONS

/* The layer is read back through two pixel buffers in turn, so the CPU maps
 * the previous frame's pixels instead of stalling on this one's. */
struct Target {
    HGLRC context = nullptr;
    GLuint framebuffer = 0;
    GLuint texture = 0;
    GLuint pixels[2] = {};
    int filledWidth[2] = {};  // 0: holds no frame
    int filledHeight[2] = {};
    POINT filledOrigin[2] = {};
    int width = 0;
    int height = 0;
    int next = 0;
    GLint replacedDraw = 0;
    GLint replacedRead = 0;
    bool drawnThisFrame = false;
};

Target g_target;

/* Bottom-up 32bpp DIB: glReadPixels returns the bottom row first too. */
struct Surface {
    HBITMAP bitmap = nullptr;
    void* bits = nullptr;
    int width = 0;
    int height = 0;
    POINT origin{};
};

/* Triple buffer between the render thread (owns g_writeSlot) and the window
 * thread (owns g_presentSlot); g_readySlot changes hands under g_slotMutex. */
Surface g_surfaces[3];
int g_writeSlot = 0;
int g_readySlot = 1;
int g_presentSlot = 2;
bool g_readyFresh = false;
std::mutex g_slotMutex;

std::atomic<bool> g_wantVisible{false};
std::atomic<ULONGLONG> g_lastPublish{0};
std::atomic<HWND> g_game{nullptr};

HANDLE g_windowThread = nullptr;
DWORD g_windowThreadId = 0;
HANDLE g_wakeEvent = nullptr;
HWND g_window = nullptr;  // written by the window thread before it signals ready
bool g_unavailable = false;

bool excludeFromCaptureSupported() {
    using RtlGetVersion = LONG(WINAPI*)(OSVERSIONINFOW*);
    const auto getVersion = reinterpret_cast<RtlGetVersion>(
        GetProcAddress(GetModuleHandleW(L"ntdll.dll"), "RtlGetVersion"));
    OSVERSIONINFOW version{};
    version.dwOSVersionInfoSize = sizeof(version);
    return getVersion != nullptr && getVersion(&version) == 0 &&
           version.dwBuildNumber >= kExcludeFromCaptureBuild;
}

/*
 * UpdateLayeredWindow wants premultiplied BGRA. Colour blended over the
 * cleared layer with SRC_ALPHA is premultiplied already, but alpha is only
 * right where the draw used a separate alpha factor; a plain glBlendFunc
 * squares it. No premultiplied pixel has a channel above its alpha, so
 * raising alpha to the brightest channel keeps every pixel valid. Done on
 * the window thread, off the game's frame.
 */
void premultiply(Surface& surface) {
    auto* pixel = static_cast<std::uint8_t*>(surface.bits);
    const std::size_t count = static_cast<std::size_t>(surface.width) * static_cast<std::size_t>(surface.height);
    for (std::size_t i = 0; i < count; ++i, pixel += 4) {
        pixel[3] = std::max({pixel[3], pixel[0], pixel[1], pixel[2]});
    }
}

bool presentLatest(HWND window, HDC screen, HDC memory) {
    {
        std::lock_guard<std::mutex> lock(g_slotMutex);
        if (!g_readyFresh) {
            return false;
        }
        std::swap(g_presentSlot, g_readySlot);
        g_readyFresh = false;
    }
    Surface& surface = g_surfaces[g_presentSlot];
    premultiply(surface);
    const HGDIOBJ previous = SelectObject(memory, surface.bitmap);
    POINT origin = surface.origin;
    SIZE size{surface.width, surface.height};
    POINT source{0, 0};
    BLENDFUNCTION blend{AC_SRC_OVER, 0, 255, AC_SRC_ALPHA};
    UpdateLayeredWindow(window, screen, &origin, &size, memory, &source, 0, &blend, ULW_ALPHA);
    SelectObject(memory, previous);
    return true;
}

DWORD WINAPI windowThreadMain(LPVOID ready) {
    const HINSTANCE instance = GetModuleHandleW(nullptr);
    /* The default procedure keeps none of this DLL's code on the window's message path. */
    WNDCLASSEXW windowClass{};
    windowClass.cbSize = sizeof(windowClass);
    windowClass.lpfnWndProc = DefWindowProcW;
    windowClass.hInstance = instance;
    windowClass.lpszClassName = kWindowClass;
    RegisterClassExW(&windowClass);

    const HWND window = CreateWindowExW(
        WS_EX_LAYERED | WS_EX_TRANSPARENT | WS_EX_TOPMOST | WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE,
        kWindowClass, L"", WS_POPUP, 0, 0, 1, 1, nullptr, nullptr, instance, nullptr);
    if (window == nullptr || !SetWindowDisplayAffinity(window, kExcludeFromCapture)) {
        note("capture-excluded window unavailable (error %lu)", GetLastError());
        if (window != nullptr) {
            DestroyWindow(window);
        }
        UnregisterClassW(kWindowClass, instance);
        SetEvent(static_cast<HANDLE>(ready));
        return 1;
    }
    g_window = window;
    SetEvent(static_cast<HANDLE>(ready));

    const HDC screen = GetDC(nullptr);
    const HDC memory = CreateCompatibleDC(screen);
    bool presented = false;
    bool shown = false;
    bool running = true;
    while (running) {
        /* The timeout keeps the foreground check going while a minimized game stops swapping. */
        MsgWaitForMultipleObjects(1, &g_wakeEvent, FALSE, 100, QS_ALLINPUT);
        MSG message;
        while (PeekMessageW(&message, nullptr, 0, 0, PM_REMOVE)) {
            if (message.message == WM_QUIT) {
                running = false;
            } else {
                DispatchMessageW(&message);
            }
        }
        if (!running) {
            break;
        }
        if (presentLatest(window, screen, memory)) {
            presented = true;
        }

        /* Topmost over everything, so it goes the moment the game does; after
         * a hide it waits for a fresh frame rather than show stale pixels. */
        const bool wanted = g_wantVisible.load();
        if (!wanted) {
            presented = false;
        }
        const HWND game = g_game.load();
        const bool fresh = GetTickCount64() - g_lastPublish.load() < kFrameTimeoutMillis;
        const bool visible = presented && wanted && fresh && game != nullptr &&
                             GetForegroundWindow() == game && !IsIconic(game);
        if (visible != shown) {
            if (visible) {
                SetWindowPos(window, HWND_TOPMOST, 0, 0, 0, 0,
                             SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE | SWP_SHOWWINDOW);
            } else {
                ShowWindow(window, SW_HIDE);
            }
            shown = visible;
        }
    }

    DeleteDC(memory);
    ReleaseDC(nullptr, screen);
    DestroyWindow(window);
    UnregisterClassW(kWindowClass, instance);
    return 0;
}

bool startWindow() {
    if (g_window != nullptr) {
        return true;
    }
    if (!excludeFromCaptureSupported()) {
        note("needs Windows 10 build %lu or newer", kExcludeFromCaptureBuild);
        return false;
    }
    g_wakeEvent = CreateEventW(nullptr, FALSE, FALSE, nullptr);
    HANDLE ready = CreateEventW(nullptr, TRUE, FALSE, nullptr);
    g_windowThread = CreateThread(nullptr, 0, windowThreadMain, ready, 0, &g_windowThreadId);
    if (g_windowThread != nullptr) {
        WaitForSingleObject(ready, INFINITE);
    }
    CloseHandle(ready);
    if (g_window != nullptr) {
        return true;
    }
    if (g_windowThread != nullptr) {
        WaitForSingleObject(g_windowThread, INFINITE);
        CloseHandle(g_windowThread);
        g_windowThread = nullptr;
    }
    CloseHandle(g_wakeEvent);
    g_wakeEvent = nullptr;
    return false;
}

void publish(const std::uint8_t* pixels, int width, int height, POINT origin) {
    Surface& surface = g_surfaces[g_writeSlot];
    if (surface.width != width || surface.height != height) {
        if (surface.bitmap != nullptr) {
            DeleteObject(surface.bitmap);
        }
        BITMAPINFO info{};
        info.bmiHeader.biSize = sizeof(info.bmiHeader);
        info.bmiHeader.biWidth = width;
        info.bmiHeader.biHeight = height;
        info.bmiHeader.biPlanes = 1;
        info.bmiHeader.biBitCount = 32;
        info.bmiHeader.biCompression = BI_RGB;
        surface = Surface{};
        surface.bitmap = CreateDIBSection(nullptr, &info, DIB_RGB_COLORS, &surface.bits, nullptr, 0);
        if (surface.bitmap == nullptr) {
            surface = Surface{};
            return;
        }
        surface.width = width;
        surface.height = height;
    }
    std::memcpy(surface.bits, pixels, static_cast<std::size_t>(width) * static_cast<std::size_t>(height) * 4);
    surface.origin = origin;
    {
        std::lock_guard<std::mutex> lock(g_slotMutex);
        std::swap(g_writeSlot, g_readySlot);
        g_readyFresh = true;
    }
    g_lastPublish = GetTickCount64();
}

void forgetFrames() {
    g_target.filledWidth[0] = g_target.filledWidth[1] = 0;
    std::lock_guard<std::mutex> lock(g_slotMutex);
    g_readyFresh = false;
}

void hide() {
    if (!g_wantVisible.exchange(false)) {
        return;
    }
    /* Frames still in flight predate the hide and would flash on the next show. */
    forgetFrames();
    SetEvent(g_wakeEvent);
}

void deleteObjects() {
    if (g_target.context != nullptr && g_target.context == wglGetCurrentContext()) {
        glDeleteFramebuffers(1, &g_target.framebuffer);
        glDeleteTextures(1, &g_target.texture);
        glDeleteBuffers(2, g_target.pixels);
    }
    g_target = Target{};
}

/* Names from another context mean nothing in this one; a world switch on 1.21 deletes them outright. */
bool ensureObjects() {
    const HGLRC context = wglGetCurrentContext();
    if (g_target.context == context && g_target.framebuffer != 0 &&
        glIsFramebuffer(g_target.framebuffer) && glIsTexture(g_target.texture)) {
        return true;
    }
    if (g_target.context == context) {
        deleteObjects();
    }
    g_target = Target{};
    g_target.context = context;
    glGenFramebuffers(1, &g_target.framebuffer);
    glGenTextures(1, &g_target.texture);
    glGenBuffers(2, g_target.pixels);
    return g_target.framebuffer != 0 && g_target.texture != 0 && g_target.pixels[0] != 0 &&
           g_target.pixels[1] != 0;
}

/* Called with the layer bound. */
void resize(int width, int height) {
    GLint texture = 0;
    GLint unpack = 0;
    GLint pack = 0;
    glGetIntegerv(GL_TEXTURE_BINDING_2D, &texture);
    glGetIntegerv(kPixelUnpackBufferBinding, &unpack);
    glGetIntegerv(kPixelPackBufferBinding, &pack);

    glBindBuffer(kPixelUnpackBuffer, 0);
    glBindTexture(GL_TEXTURE_2D, g_target.texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexImage2D(GL_TEXTURE_2D, 0, kRgba8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glFramebufferTexture2D(kFramebuffer, kColorAttachment0, GL_TEXTURE_2D, g_target.texture, 0);
    for (int i = 0; i < 2; ++i) {
        glBindBuffer(kPixelPackBuffer, g_target.pixels[i]);
        glBufferData(kPixelPackBuffer, static_cast<std::ptrdiff_t>(width) * height * 4, nullptr,
                     kStreamRead);
    }
    forgetFrames();

    glBindBuffer(kPixelPackBuffer, static_cast<GLuint>(pack));
    glBindBuffer(kPixelUnpackBuffer, static_cast<GLuint>(unpack));
    glBindTexture(GL_TEXTURE_2D, static_cast<GLuint>(texture));
    g_target.width = width;
    g_target.height = height;
}

/* The depth of the target being replaced, so world draws in the layer are hidden by the world as before. */
void shareDepth(GLint replaced) {
    GLint type = GL_NONE;
    GLint name = 0;
    if (replaced != 0) {
        glGetFramebufferAttachmentParameteriv(kDrawFramebuffer, kDepthAttachment,
                                              kAttachmentObjectType, &type);
        if (type != GL_NONE) {
            glGetFramebufferAttachmentParameteriv(kDrawFramebuffer, kDepthAttachment,
                                                  kAttachmentObjectName, &name);
        }
    }
    glBindFramebuffer(kFramebuffer, g_target.framebuffer);
    if (type == static_cast<GLint>(kRenderbuffer)) {
        glFramebufferRenderbuffer(kFramebuffer, kDepthAttachment, kRenderbuffer,
                                  static_cast<GLuint>(name));
    } else if (type == GL_TEXTURE) {
        glFramebufferTexture2D(kFramebuffer, kDepthAttachment, GL_TEXTURE_2D,
                               static_cast<GLuint>(name), 0);
    } else {
        glFramebufferRenderbuffer(kFramebuffer, kDepthAttachment, kRenderbuffer, 0);
    }
}

void clearLayer() {
    GLboolean scissor = glIsEnabled(GL_SCISSOR_TEST);
    GLboolean mask[4] = {};
    GLfloat colour[4] = {};
    glGetBooleanv(GL_COLOR_WRITEMASK, mask);
    glGetFloatv(GL_COLOR_CLEAR_VALUE, colour);
    glDisable(GL_SCISSOR_TEST);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    glClearColor(colour[0], colour[1], colour[2], colour[3]);
    glColorMask(mask[0], mask[1], mask[2], mask[3]);
    if (scissor) {
        glEnable(GL_SCISSOR_TEST);
    }
}

void restoreReplaced() {
    glBindFramebuffer(kDrawFramebuffer, static_cast<GLuint>(g_target.replacedDraw));
    glBindFramebuffer(kReadFramebuffer, static_cast<GLuint>(g_target.replacedRead));
}

void disable(const char* reason) {
    note("%s; Stream Proof modules draw in the game's frame", reason);
    g_unavailable = true;
    hide();
}

}  // namespace

void setLogger(Logger logger) { g_logger = logger; }

bool begin() {
    if (g_unavailable) {
        return false;
    }
    static const bool glLoaded = loadGl();
    if (!glLoaded) {
        disable("GL 3.0 framebuffer functions missing");
        return false;
    }
    if (!startWindow()) {
        disable("no capture-excluded window");
        return false;
    }
    if (!ensureObjects()) {
        disable("layer objects could not be created");
        return false;
    }

    GLint viewport[4] = {};
    glGetIntegerv(GL_VIEWPORT, viewport);
    glGetIntegerv(kDrawFramebufferBinding, &g_target.replacedDraw);
    glGetIntegerv(kReadFramebufferBinding, &g_target.replacedRead);
    const int width = viewport[0] + viewport[2];
    const int height = viewport[1] + viewport[3];
    if (width <= 0 || height <= 0) {
        return true;
    }

    shareDepth(g_target.replacedDraw);
    if (g_target.width != width || g_target.height != height) {
        resize(width, height);
        g_target.drawnThisFrame = false;
    }
    if (glCheckFramebufferStatus(kFramebuffer) != kFramebufferComplete) {
        /* A depth the layer cannot take only costs the world draws their occlusion. */
        glFramebufferRenderbuffer(kFramebuffer, kDepthAttachment, kRenderbuffer, 0);
        if (glCheckFramebufferStatus(kFramebuffer) != kFramebufferComplete) {
            restoreReplaced();
            disable("incomplete layer framebuffer");
            return false;
        }
    }
    if (!g_target.drawnThisFrame) {
        clearLayer();
        g_target.drawnThisFrame = true;
    }
    return true;
}

void end() { restoreReplaced(); }

void present() {
    if (g_unavailable) {
        return;
    }
    if (!g_target.drawnThisFrame) {
        hide();
        return;
    }
    g_target.drawnThisFrame = false;

    GLint read = 0;
    GLint pack = 0;
    GLint alignment = 4;
    GLint rowLength = 0;
    GLint skipRows = 0;
    GLint skipPixels = 0;
    glGetIntegerv(kReadFramebufferBinding, &read);
    glGetIntegerv(kPixelPackBufferBinding, &pack);
    glGetIntegerv(GL_PACK_ALIGNMENT, &alignment);
    glGetIntegerv(GL_PACK_ROW_LENGTH, &rowLength);
    glGetIntegerv(GL_PACK_SKIP_ROWS, &skipRows);
    glGetIntegerv(GL_PACK_SKIP_PIXELS, &skipPixels);
    glPixelStorei(GL_PACK_ALIGNMENT, 4);
    glPixelStorei(GL_PACK_ROW_LENGTH, 0);
    glPixelStorei(GL_PACK_SKIP_ROWS, 0);
    glPixelStorei(GL_PACK_SKIP_PIXELS, 0);

    const HWND game = WindowFromDC(wglGetCurrentDC());
    POINT origin{0, 0};
    ClientToScreen(game, &origin);

    const int current = g_target.next;
    glBindFramebuffer(kReadFramebuffer, g_target.framebuffer);
    glBindBuffer(kPixelPackBuffer, g_target.pixels[current]);
    glReadPixels(0, 0, g_target.width, g_target.height, kBgra, GL_UNSIGNED_BYTE, nullptr);
    g_target.filledWidth[current] = g_target.width;
    g_target.filledHeight[current] = g_target.height;
    g_target.filledOrigin[current] = origin;

    const int previous = current ^ 1;
    g_target.next = previous;
    if (g_target.filledWidth[previous] != 0) {
        glBindBuffer(kPixelPackBuffer, g_target.pixels[previous]);
        if (const void* pixels = glMapBuffer(kPixelPackBuffer, kReadOnly)) {
            publish(static_cast<const std::uint8_t*>(pixels), g_target.filledWidth[previous],
                    g_target.filledHeight[previous], g_target.filledOrigin[previous]);
            glUnmapBuffer(kPixelPackBuffer);
        }
    }

    glPixelStorei(GL_PACK_ALIGNMENT, alignment);
    glPixelStorei(GL_PACK_ROW_LENGTH, rowLength);
    glPixelStorei(GL_PACK_SKIP_ROWS, skipRows);
    glPixelStorei(GL_PACK_SKIP_PIXELS, skipPixels);
    glBindBuffer(kPixelPackBuffer, static_cast<GLuint>(pack));
    glBindFramebuffer(kReadFramebuffer, static_cast<GLuint>(read));

    g_game = game;
    g_wantVisible = true;
    SetEvent(g_wakeEvent);
}

void shutdown() {
    if (g_target.context != nullptr) {
        deleteObjects();
    }
    stopWindow();
}

void stopWindow() {
    if (g_windowThread != nullptr) {
        PostThreadMessageW(g_windowThreadId, WM_QUIT, 0, 0);
        WaitForSingleObject(g_windowThread, INFINITE);
        CloseHandle(g_windowThread);
        g_windowThread = nullptr;
        CloseHandle(g_wakeEvent);
        g_wakeEvent = nullptr;
        g_window = nullptr;
    }
    g_wantVisible = false;
    for (Surface& surface : g_surfaces) {
        if (surface.bitmap != nullptr) {
            DeleteObject(surface.bitmap);
        }
        surface = Surface{};
    }
    g_readyFresh = false;
}

}  // namespace streamproof
