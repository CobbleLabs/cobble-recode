#include "core/TestHook.hpp"

#include <windows.h>

#include <cstdio>
#include <algorithm>
#include <cstdlib>
#include <sstream>
#include <vector>

#include "bridge/Minecraft.hpp"
#include "core/Build.hpp"
#include "core/Log.hpp"
#include "game/PlayerRadar.hpp"
#include "game/Raycast.hpp"
#include "game/RotationEngine.hpp"
#include "modules/Module.hpp"
#include "hooks/Input.hpp"
#include "modules/ModuleManager.hpp"
#include "render/Gl.hpp"
#include "render/Renderer.hpp"
#include "script/Script.hpp"
#include "script/ScriptManager.hpp"
#include "ui/Client.hpp"
#include "ui/ClickGui.hpp"
#include "ui/Hud.hpp"

namespace cold::test {
namespace {

constexpr std::string_view kScope = "control";
constexpr int kPollInterval = 20;

std::string readAll(const std::wstring& path) {
    HANDLE file = CreateFileW(path.c_str(), GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE,
                              nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (file == INVALID_HANDLE_VALUE) {
        return {};
    }
    LARGE_INTEGER size;
    std::string content;
    if (GetFileSizeEx(file, &size) && size.QuadPart > 0 && size.QuadPart < (1 << 20)) {
        content.resize(static_cast<std::size_t>(size.QuadPart));
        DWORD read = 0;
        ReadFile(file, content.data(), static_cast<DWORD>(content.size()), &read, nullptr);
        content.resize(read);
    }
    CloseHandle(file);
    return content;
}

void writeAll(const std::wstring& path, const std::string& content) {
    HANDLE file = CreateFileW(path.c_str(), GENERIC_WRITE, FILE_SHARE_READ, nullptr, CREATE_ALWAYS,
                              FILE_ATTRIBUTE_NORMAL, nullptr);
    if (file == INVALID_HANDLE_VALUE) {
        return;
    }
    DWORD written = 0;
    WriteFile(file, content.data(), static_cast<DWORD>(content.size()), &written, nullptr);
    CloseHandle(file);
}

std::wstring widen(const std::string& value) {
    const int length = MultiByteToWideChar(CP_UTF8, 0, value.c_str(), -1, nullptr, 0);
    std::wstring wide(static_cast<std::size_t>(length > 0 ? length - 1 : 0), L'\0');
    if (length > 1) {
        MultiByteToWideChar(CP_UTF8, 0, value.c_str(), -1, wide.data(), length);
    }
    return wide;
}

#pragma pack(push, 1)
struct BitmapFileHeader {
    std::uint16_t type;
    std::uint32_t size;
    std::uint16_t reserved1;
    std::uint16_t reserved2;
    std::uint32_t offset;
};
struct BitmapInfoHeader {
    std::uint32_t size;
    std::int32_t width;
    std::int32_t height;
    std::uint16_t planes;
    std::uint16_t bitCount;
    std::uint32_t compression;
    std::uint32_t imageSize;
    std::int32_t xPixelsPerMetre;
    std::int32_t yPixelsPerMetre;
    std::uint32_t coloursUsed;
    std::uint32_t coloursImportant;
};
#pragma pack(pop)

/* 24-bit bottom-up BMP. Nothing here needs a PNG encoder, and the test script
 * converts on the way out. */
void writeBitmap(const std::wstring& path, const std::vector<std::uint8_t>& bgr, int width,
                 int height) {
    const std::uint32_t rowStride = (static_cast<std::uint32_t>(width) * 3u + 3u) & ~3u;
    const std::uint32_t imageSize = rowStride * static_cast<std::uint32_t>(height);

    BitmapFileHeader fileHeader{};
    fileHeader.type = 0x4D42;
    fileHeader.offset = sizeof(BitmapFileHeader) + sizeof(BitmapInfoHeader);
    fileHeader.size = fileHeader.offset + imageSize;

    BitmapInfoHeader infoHeader{};
    infoHeader.size = sizeof(BitmapInfoHeader);
    infoHeader.width = width;
    infoHeader.height = height;
    infoHeader.planes = 1;
    infoHeader.bitCount = 24;
    infoHeader.imageSize = imageSize;

    std::string content;
    content.reserve(fileHeader.size);
    content.append(reinterpret_cast<const char*>(&fileHeader), sizeof(fileHeader));
    content.append(reinterpret_cast<const char*>(&infoHeader), sizeof(infoHeader));
    const std::string padding(rowStride - static_cast<std::uint32_t>(width) * 3u, '\0');
    for (int row = 0; row < height; ++row) {
        content.append(reinterpret_cast<const char*>(
                           &bgr[static_cast<std::size_t>(row) * static_cast<std::size_t>(width) * 3]),
                       static_cast<std::size_t>(width) * 3);
        content.append(padding);
    }
    writeAll(path, content);
}

}  // namespace

Control& control() {
    static Control instance;
    return instance;
}

void Control::configure(const std::wstring& directory) {
    directory_ = directory;
    requestPath_ = directory + L"\\cobble.control";
    replyPath_ = directory + L"\\cobble.control.out";
    /* Drop anything left over from a previous session. A request file that
     * outlived the client it was meant for would otherwise be executed by the
     * next one on its first frame — including `unload`. */
    if (DeleteFileW(requestPath_.c_str()) != FALSE) {
        COLD_WARN(kScope, "discarded a stale control request");
    }
    DeleteFileW(replyPath_.c_str());
    enabled_ = true;
}

bool Control::capture(const std::string& fileName, int width, int height, std::string& reply) {
    if (width <= 0 || height <= 0) {
        reply = "no framebuffer";
        return false;
    }
    std::vector<std::uint8_t> pixels(static_cast<std::size_t>(width) *
                                     static_cast<std::size_t>(height) * 3);

    /* glReadPixels stalls the pipeline, which is exactly what a scripted
     * verification run wants: the bytes are the frame that was just presented. */
    {
        gl::PixelTransferGuard transfer(false);
        gl::BindFramebuffer(GL_READ_FRAMEBUFFER, 0);
        glReadPixels(0, 0, width, height, GL_RGB, GL_UNSIGNED_BYTE, pixels.data());
    }

    /* BMP stores BGR bottom-up, which is the row order glReadPixels already
     * gives, so only the channel swap is needed. */
    for (std::size_t index = 0; index + 2 < pixels.size(); index += 3) {
        std::swap(pixels[index], pixels[index + 2]);
    }
    writeBitmap(directory_ + L"\\" + widen(fileName), pixels, width, height);
    reply = "wrote " + fileName + " (" + std::to_string(width) + "x" + std::to_string(height) + ")";
    return true;
}

bool Control::execute(const std::string& line, modules::Context& context, std::string& reply) {
    std::istringstream stream(line);
    std::string command;
    stream >> command;
    if (command.empty() || command[0] == '#') {
        return true;
    }

    if (command == "gui") {
        std::string value;
        stream >> value;
        modules::Module* module = modules::modules().find("ClickGUI");
        if (module == nullptr) {
            reply = "ClickGUI missing";
            return false;
        }
        module->setEnabled(value != "off", &context);
        reply = std::string("gui ") + (value != "off" ? "open" : "closed");
        return true;
    }

    if (command == "hold") {
        std::string which;
        std::string value;
        stream >> which >> value;
        const bool down = value != "off" && value != "false" && value != "0";
        if (which == "use" || which == "attack") {
            const int button = which == "use" ? hooks::kMouseRight : hooks::kMouseLeft;
            hooks::input().setSyntheticButton(button, down);
            reply = "hold " + which + (down ? " down" : " up");
            return true;
        }
        /* Movement keys go through as synthetic presses so scripted runs can
         * walk. Anything wider would be a keyboard emulator, and the suite
         * has no use for one. */
        static const struct {
            const char* name;
            int code;
        } kKeys[] = {
            {"w", 87},     {"a", 65},     {"s", 83},          {"d", 68},
            {"space", 32}, {"shift", 340}, {"ctrl", 341},
        };
        for (const auto& entry : kKeys) {
            if (which == entry.name) {
                hooks::input().sendKey(entry.code, down);
                reply = "hold " + which + (down ? " down" : " up");
                return true;
            }
        }
        reply = "hold takes use, attack, w, a, s, d, space, shift or ctrl";
        return false;
    }

    if (command == "module") {
        std::string name;
        std::string value;
        stream >> name >> value;
        modules::Module* module = modules::modules().find(name);
        if (module == nullptr) {
            reply = "unknown module " + name;
            return false;
        }
        if (value == "toggle") {
            module->toggle(&context);
        } else {
            module->setEnabled(value != "off", &context);
        }
        reply = name + " " + (module->enabled() ? "on" : "off");
        return true;
    }

    if (command == "script") {
        std::string action;
        std::string file;
        stream >> action >> file;

        if (action == "list") {
            reply.clear();
            for (const std::unique_ptr<script::Script>& entry : script::manager().scripts()) {
                if (!reply.empty()) {
                    reply += "; ";
                }
                reply += entry->file() + " " + entry->name() + " " + entry->version() + " " +
                         (script::manager().enabled(entry->file()) ? "on" : "off") + " " +
                         (entry->running() ? "loaded" : "stopped");
                if (!entry->error().empty()) {
                    reply += " error=" + entry->error();
                }
            }
            if (reply.empty()) {
                reply = "no scripts";
            }
            return true;
        }
        if (action == "reload") {
            /* Deferred like every other script change: it lands at the top of
             * the next frame, never in the middle of this one. */
            script::manager().requestReload(file);
            reply = file.empty() ? "reloading every script" : "reloading " + file;
            return true;
        }
        if (action == "on" || action == "off") {
            if (script::manager().find(file) == nullptr) {
                reply = "unknown script " + file;
                return false;
            }
            script::manager().requestEnable(file, action == "on");
            reply = file + " " + action;
            return true;
        }
        reply = "script takes list, reload or on/off";
        return false;
    }

    if (command == "set") {
        std::string moduleName;
        std::string settingName;
        std::string value;
        stream >> moduleName;
        /* Setting names have spaces in them ("Turn speed", "Telly air only"),
         * so take the rest of the line and split the value off the end. */
        std::string rest;
        std::getline(stream, rest);
        const std::size_t first = rest.find_first_not_of(" 	");
        const std::size_t last = rest.find_last_not_of(" 	");
        if (first == std::string::npos) {
            reply = "set needs a module, a setting and a value";
            return false;
        }
        rest = rest.substr(first, last - first + 1);
        const std::size_t split = rest.find_last_of(" 	");
        if (split == std::string::npos) {
            reply = "set needs a module, a setting and a value";
            return false;
        }
        settingName = rest.substr(0, rest.find_last_not_of(" 	", split) + 1);
        value = rest.substr(split + 1);
        modules::Module* module = modules::modules().find(moduleName);
        if (module == nullptr) {
            reply = "unknown module " + moduleName;
            return false;
        }
        modules::Setting* setting = module->setting(settingName);
        if (setting == nullptr) {
            reply = "unknown setting " + settingName;
            return false;
        }
        switch (setting->type()) {
            case modules::Setting::Type::Boolean:
                setting->setBoolean(value == "true" || value == "on" || value == "1");
                break;
            case modules::Setting::Type::Number:
                setting->setNumber(std::strtof(value.c_str(), nullptr));
                break;
            case modules::Setting::Type::Choice: {
                const auto& choices = setting->choices();
                bool matched = false;
                for (std::size_t index = 0; index < choices.size() && !matched; ++index) {
                    if (choices[index] == value) {
                        setting->setChoice(static_cast<int>(index));
                        matched = true;
                    }
                }
                if (!matched) {
                    reply = "unknown choice " + value + " for " + settingName;
                    return false;
                }
                break;
            }
            case modules::Setting::Type::Key:
                setting->setKey(std::atoi(value.c_str()));
                break;
            case modules::Setting::Type::Colour:
                setting->setColour(Color::rgba(
                    static_cast<std::uint32_t>(std::strtoul(value.c_str(), nullptr, 16))));
                break;
        }
        reply = moduleName + "." + settingName + " = " + value;
        return true;
    }

    if (command == "atlas") {
        std::string which;
        std::string fileName;
        stream >> which >> fileName;

        if (which == "cpufont") {
            const render::FontAtlas& atlas = render::renderer().fonts();
            const int width = atlas.atlasWidth();
            const int height = atlas.atlasHeight();
            if (width <= 0 || atlas.pixels().empty()) {
                reply = "no CPU font atlas";
                return false;
            }
            std::vector<std::uint8_t> rgb(static_cast<std::size_t>(width) *
                                          static_cast<std::size_t>(height) * 3);
            for (int row = 0; row < height; ++row) {
                const int source = height - 1 - row;
                for (int column = 0; column < width; ++column) {
                    const std::uint8_t value =
                        atlas.pixels()[static_cast<std::size_t>(source) *
                                           static_cast<std::size_t>(width) +
                                       static_cast<std::size_t>(column)];
                    const std::size_t target =
                        (static_cast<std::size_t>(row) * static_cast<std::size_t>(width) +
                         static_cast<std::size_t>(column)) *
                        3;
                    rgb[target] = value;
                    rgb[target + 1] = value;
                    rgb[target + 2] = value;
                }
            }
            writeBitmap(directory_ + L"\\" + widen(fileName), rgb, width, height);
            reply = "wrote " + fileName + " from CPU (" + std::to_string(width) + "x" +
                    std::to_string(height) + ")";
            return true;
        }

        const GLuint texture = which == "icon" ? render::renderer().icons().texture()
                                               : render::renderer().fonts().texture();
        if (texture == 0 || fileName.empty()) {
            reply = "no such atlas";
            return false;
        }
        GLint width = 0;
        GLint height = 0;
        gl::ActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, texture);
        glGetTexLevelParameteriv(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH, &width);
        glGetTexLevelParameteriv(GL_TEXTURE_2D, 0, GL_TEXTURE_HEIGHT, &height);
        if (width <= 0 || height <= 0) {
            reply = "atlas has no storage";
            return false;
        }

        std::vector<std::uint8_t> single(static_cast<std::size_t>(width) *
                                         static_cast<std::size_t>(height));
        {
            gl::PixelTransferGuard transfer(false);
            glGetTexImage(GL_TEXTURE_2D, 0, GL_RED, GL_UNSIGNED_BYTE, single.data());
        }

        /* Expand to grey so the BMP writer below can stay 24-bit, and flip
         * vertically because BMP rows run bottom-up. */
        std::vector<std::uint8_t> rgb(single.size() * 3);
        for (int row = 0; row < height; ++row) {
            const int source = height - 1 - row;
            for (int column = 0; column < width; ++column) {
                const std::uint8_t value =
                    single[static_cast<std::size_t>(source) * static_cast<std::size_t>(width) +
                           static_cast<std::size_t>(column)];
                const std::size_t target = (static_cast<std::size_t>(row) *
                                                static_cast<std::size_t>(width) +
                                            static_cast<std::size_t>(column)) *
                                           3;
                rgb[target] = value;
                rgb[target + 1] = value;
                rgb[target + 2] = value;
            }
        }
        writeBitmap(directory_ + L"\\" + widen(fileName), rgb, width, height);
        reply = "wrote " + fileName + " (" + std::to_string(width) + "x" +
                std::to_string(height) + ")";
        return true;
    }

    if (command == "entities") {
        const game::FrameState& state = *context.state;
        std::vector<const game::EntityView*> nearest;
        for (const game::EntityView& entity : state.entities) {
            nearest.push_back(&entity);
        }
        std::sort(nearest.begin(), nearest.end(),
                  [](const game::EntityView* a, const game::EntityView* b) {
                      return a->distance < b->distance;
                  });
        reply.clear();
        const std::size_t limit = std::min<std::size_t>(nearest.size(), 6);
        for (std::size_t index = 0; index < limit; ++index) {
            const game::EntityView* entity = nearest[index];
            char line[128];
            std::snprintf(line, sizeof(line), "%s#%d hp=%.1f/%.1f d=%.2f; ",
                          entity->isPlayer ? "player" : "mob", entity->id, entity->health,
                          entity->maxHealth, entity->distance);
            reply += line;
        }
        if (reply.empty()) {
            reply = "none";
        }
        return true;
    }

    /* What the player radar has on file. The window is the only other way to
     * see it, and a screenshot cannot say whether a slot came back empty
     * because the player has nothing or because the read failed. */
    if (command == "radar") {
        std::string who;
        stream >> who;
        reply.clear();
        for (const game::RadarPlayer& player : game::radar().players()) {
            if (!who.empty() && player.name != who) {
                continue;
            }
            char line[256];
            std::snprintf(line, sizeof(line), "%s%s d=%.1f hp=%.1f ping=%d items=%d [",
                          player.name.c_str(), player.self ? "(self)" : "", player.distance,
                          player.health, player.ping, static_cast<int>(player.history.size()));
            reply += line;
            static const char* const kSlots[] = {"head", "chest", "legs",
                                                 "feet", "main",  "off"};
            for (int slot = 0; slot < static_cast<int>(game::Game::Slot::Count); ++slot) {
                const game::Game::EquipmentView& item = player.slots[slot];
                reply += kSlots[slot];
                reply += '=';
                reply += item.present ? item.id : "-";
                if (item.present && !item.enchantments.empty()) {
                    reply += '(';
                    for (const game::Game::EnchantView& enchant : item.enchantments) {
                        reply += enchant.id + std::to_string(enchant.level) + ' ';
                    }
                    reply += ')';
                }
                reply += ' ';
            }
            reply += "]; ";
        }
        if (reply.empty()) {
            reply = "no players tracked";
        }
        return true;
    }

    if (command == "respawn") {
        if (!game::Game::instance().respawn(context.env, context.state->player.handle)) {
            reply = "cannot respawn";
            return false;
        }
        reply = "respawn requested";
        return true;
    }

    if (command == "closescreen") {
        game::Game::instance().closeScreen(context.env);
        reply = "screen closed";
        return true;
    }

    if (command == "expand") {
        std::string name;
        std::string value;
        stream >> name >> value;
        if (!ui::client().gui().setExpanded(name, value != "off")) {
            reply = "unknown module " + name;
            return false;
        }
        reply = name + " settings " + (value != "off" ? "open" : "closed");
        return true;
    }

    if (command == "screen") {
        std::string name;
        stream >> name;
        if (!ui::client().gui().showScreen(name)) {
            reply = "unknown screen " + name;
            return false;
        }
        reply = "screen " + name;
        return true;
    }

    if (command == "pick") {
        std::string name;
        std::string value;
        stream >> name >> value;
        if (!ui::client().gui().setPickerOpen(name, value != "off")) {
            reply = name + " has no colour setting";
            return false;
        }
        reply = name + " picker " + (value != "off" ? "open" : "closed");
        return true;
    }

    if (command == "look") {
        std::string yawText;
        std::string pitchText;
        stream >> yawText >> pitchText;
        if (yawText.empty() || pitchText.empty()) {
            reply = "look takes a yaw and a pitch";
            return false;
        }
        const float yaw = std::strtof(yawText.c_str(), nullptr);
        const float pitch = std::strtof(pitchText.c_str(), nullptr);
        game::Game::instance().setRotation(context.env, context.state->player.handle, yaw, pitch,
                                           true);
        reply = "looking " + yawText + " " + pitchText;
        return true;
    }

    if (command == "ray") {
        /* Casts from the eye along whatever rotation the server holds and says
         * what it lands on. The scaffold picks its aim by raycast, so when it
         * reports that it cannot see a face this is how to find out why. */
        float yaw = game::RotationEngine::instance().sentYaw();
        float pitch = game::RotationEngine::instance().sentPitch();
        std::string first;
        std::string second;
        stream >> first >> second;
        if (!first.empty()) yaw = std::strtof(first.c_str(), nullptr);
        if (!second.empty()) pitch = std::strtof(second.c_str(), nullptr);

        jobject world = game::Game::instance().world(context.env);
        if (world == nullptr || !context.state->player.valid) {
            reply = "not in a world";
            return false;
        }
        const game::BlockHit hit =
            game::raycastBlocks(context.env, world, context.state->player.eyePos,
                                game::lookVector(yaw, pitch), 5.0);
        context.env->DeleteLocalRef(world);

        char buffer[256];
        if (!hit.hit) {
            std::snprintf(buffer, sizeof(buffer),
                          "eye=%.2f,%.2f,%.2f yaw=%.2f pitch=%.2f -> nothing",
                          context.state->player.eyePos.x, context.state->player.eyePos.y,
                          context.state->player.eyePos.z, yaw, pitch);
        } else {
            static const char* kFaceNames[] = {"down", "up", "north", "south", "west", "east"};
            std::snprintf(buffer, sizeof(buffer),
                          "eye=%.2f,%.2f,%.2f yaw=%.2f pitch=%.2f -> %d %d %d face=%s at %.2f",
                          context.state->player.eyePos.x, context.state->player.eyePos.y,
                          context.state->player.eyePos.z, yaw, pitch, hit.x, hit.y, hit.z,
                          kFaceNames[static_cast<int>(hit.face)], hit.distance);
        }
        reply = buffer;
        return true;
    }

    if (command == "cmd" || command == "say") {
        std::string rest;
        std::getline(stream, rest);
        while (!rest.empty() && rest.front() == ' ') rest.erase(rest.begin());
        if (rest.empty()) {
            reply = "nothing to send";
            return false;
        }
        if (!game::Game::instance().sendChat(context.env, rest, command == "cmd")) {
            reply = "not connected";
            return false;
        }
        reply = command + " " + rest;
        return true;
    }

    if (command == "auth") {
        reply = "local session (no licence checks in this build)";
        return true;
    }

    if (command == "cloud") {
        reply = "cloud sync not compiled in this build";
        return false;
    }

    if (command == "unload") {
        ui::client().requestShutdown();
        reply = "unload requested";
        return true;
    }

    if (command == "gpu") {
        render::Renderer& gfx = render::renderer();
        if (!gfx.gpuExpansionAvailable()) {
            reply = "no compute path on this context";
            return false;
        }
        std::string value;
        stream >> value;
        gfx.setGpuExpansion(value != "off");
        reply = std::string("quad expansion on the ") + (gfx.gpuExpansion() ? "GPU" : "CPU");
        return true;
    }

    if (command == "hud") {
        reply = ui::client().hud().debugLine(*context.state);
        return true;
    }

    if (command == "state") {
        const game::FrameState& state = *context.state;
        char buffer[640];
        const game::RotationEngine& aim = game::RotationEngine::instance();
        std::snprintf(buffer, sizeof(buffer),
                      "flavour=%s world=%d screen=%d fps=%d ping=%d fb=%dx%d entities=%zu overlay=%.0fus "
                      "frames=%lld ticks=%lld age=%d aim=%s step=%.4f silent=%lld/%lld probe=%d/%d "
                       "scripts=%d/%zu modules=%zu gpu=%d batch=%zu "
                       "pos=%.2f,%.2f,%.2f yaw=%.3f pitch=%.3f sentyaw=%.3f sentpitch=%.3f "
                       "sprint=%d",
                      build::kFlavour,
                      state.inWorld ? 1 : 0, state.screenOpen ? 1 : 0, state.fps, state.ping,
                      state.windowWidth, state.windowHeight, state.entities.size(),
                      ui::client().overlayMicros(), ui::client().frameCount(),
                      ui::client().tickCount(), state.player.age,
                      aim.engaged() ? "engaged" : "idle", aim.step(), aim.silentDelivered(),
                      aim.silentDelivered() + aim.silentMissed(), aim.probesBeforeTick(),
                      aim.probesPerArm(), script::manager().loadedCount(),
                      script::manager().scripts().size(), modules::modules().all().size(),
                      render::renderer().gpuExpansion() ? 1 : 0,
                      render::renderer().batchSize(),
                       state.player.pos.x,
                       state.player.pos.y, state.player.pos.z, state.player.yaw,
                       state.player.pitch, aim.sentYaw(), aim.sentPitch(),
                       state.player.sprinting ? 1 : 0);
        reply = buffer;
        return true;
    }

    reply = "unknown command " + command;
    return false;
}

void Control::poll(modules::Context& context, int framebufferWidth, int framebufferHeight) {
    if (!enabled_) {
        return;
    }

    if (pendingShotFrames_ > 0 && --pendingShotFrames_ == 0) {
        std::string reply;
        const bool ok = capture(pendingShotName_, framebufferWidth, framebufferHeight, reply);
        COLD_INFO(kScope, "shot %s -> %s", ok ? "ok" : "failed", reply.c_str());
        writeAll(replyPath_, std::string(ok ? "ok   " : "fail ") + reply + "\n");
        pendingShotName_.clear();
        return;
    }

    if (--countdown_ > 0) {
        return;
    }
    countdown_ = kPollInterval;

    if (GetFileAttributesW(requestPath_.c_str()) == INVALID_FILE_ATTRIBUTES) {
        return;
    }
    const std::string script = readAll(requestPath_);
    if (script.empty()) {
        /* A writer that truncates before writing leaves the file empty for a
         * moment. Deleting it here would swallow the command outright, so leave
         * it and look again next frame. */
        return;
    }
    DeleteFileW(requestPath_.c_str());
    if (script.empty()) {
        return;
    }

    std::string output;
    std::istringstream lines(script);
    std::string line;
    /* Shots are deferred to the end of the batch so the commands ahead of them
     * have already been drawn at least once. */
    std::string pendingShot;
    while (std::getline(lines, line)) {
        while (!line.empty() && (line.back() == '\r' || line.back() == ' ')) line.pop_back();
        if (line.empty()) continue;
        if (line.rfind("shot", 0) == 0) {
            std::istringstream stream(line);
            std::string command;
            stream >> command >> pendingShot;
            continue;
        }
        std::string reply = "ok";
        const bool ok = execute(line, context, reply);
        output += (ok ? "ok   " : "fail ");
        output += reply;
        output += '\n';
        COLD_INFO(kScope, "%s -> %s", line.c_str(), reply.c_str());
    }

    if (!pendingShot.empty()) {
        pendingShotName_ = pendingShot;
        pendingShotFrames_ = 45;
        output += "ok   shot queued\n";
    }
    writeAll(replyPath_, output);
}

}  // namespace cold::test
