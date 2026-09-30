#pragma once

#include <string>

namespace cold::ui {

/* GLFW key code to a short label for the bind buttons. Only the keys someone
 * would actually bind a module to are named; anything else falls back to its
 * code so the button never renders empty. */
inline std::string keyName(int key) {
    if (key == 0) {
        return "none";
    }
    if (key >= 'A' && key <= 'Z') {
        return std::string(1, static_cast<char>(key));
    }
    if (key >= '0' && key <= '9') {
        return std::string(1, static_cast<char>(key));
    }
    switch (key) {
        case 32: return "space";
        case 39: return "'";
        case 44: return ",";
        case 45: return "-";
        case 46: return ".";
        case 47: return "/";
        case 59: return ";";
        case 61: return "=";
        case 91: return "[";
        case 92: return "\\";
        case 93: return "]";
        case 96: return "`";
        case 256: return "esc";
        case 257: return "enter";
        case 258: return "tab";
        case 259: return "back";
        case 260: return "ins";
        case 261: return "del";
        case 262: return "right";
        case 263: return "left";
        case 264: return "down";
        case 265: return "up";
        case 266: return "pgup";
        case 267: return "pgdn";
        case 268: return "home";
        case 269: return "end";
        case 280: return "caps";
        case 284: return "pause";
        case 290: return "f1";
        case 291: return "f2";
        case 292: return "f3";
        case 293: return "f4";
        case 294: return "f5";
        case 295: return "f6";
        case 296: return "f7";
        case 297: return "f8";
        case 298: return "f9";
        case 299: return "f10";
        case 300: return "f11";
        case 301: return "f12";
        case 340: return "lshift";
        case 341: return "lctrl";
        case 342: return "lalt";
        case 344: return "rshift";
        case 345: return "rctrl";
        case 346: return "ralt";
        default: break;
    }
    if (key >= 320 && key <= 329) {
        return "num" + std::to_string(key - 320);
    }
    return "#" + std::to_string(key);
}

}  // namespace cold::ui
