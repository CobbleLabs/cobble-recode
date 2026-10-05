package cobble.hook;

import org.lwjgl.input.Keyboard;

/** LWJGL 2 key codes, which the legacy code speaks on every version, against the GLFW ones the rest of the client does. */
public final class LwjglKeys {
    private static final int[] toLwjgl = new int[Input.LAST_KEY + 1];
    private static final int[] toGlfw = new int[256];

    static {
        int[] pairs = {
            32, Keyboard.KEY_SPACE, 39, Keyboard.KEY_APOSTROPHE, 44, Keyboard.KEY_COMMA,
            45, Keyboard.KEY_MINUS, 46, Keyboard.KEY_PERIOD, 47, Keyboard.KEY_SLASH,
            48, Keyboard.KEY_0, 49, Keyboard.KEY_1, 50, Keyboard.KEY_2, 51, Keyboard.KEY_3,
            52, Keyboard.KEY_4, 53, Keyboard.KEY_5, 54, Keyboard.KEY_6, 55, Keyboard.KEY_7,
            56, Keyboard.KEY_8, 57, Keyboard.KEY_9, 59, Keyboard.KEY_SEMICOLON, 61, Keyboard.KEY_EQUALS,
            65, Keyboard.KEY_A, 66, Keyboard.KEY_B, 67, Keyboard.KEY_C, 68, Keyboard.KEY_D,
            69, Keyboard.KEY_E, 70, Keyboard.KEY_F, 71, Keyboard.KEY_G, 72, Keyboard.KEY_H,
            73, Keyboard.KEY_I, 74, Keyboard.KEY_J, 75, Keyboard.KEY_K, 76, Keyboard.KEY_L,
            77, Keyboard.KEY_M, 78, Keyboard.KEY_N, 79, Keyboard.KEY_O, 80, Keyboard.KEY_P,
            81, Keyboard.KEY_Q, 82, Keyboard.KEY_R, 83, Keyboard.KEY_S, 84, Keyboard.KEY_T,
            85, Keyboard.KEY_U, 86, Keyboard.KEY_V, 87, Keyboard.KEY_W, 88, Keyboard.KEY_X,
            89, Keyboard.KEY_Y, 90, Keyboard.KEY_Z,
            91, Keyboard.KEY_LBRACKET, 92, Keyboard.KEY_BACKSLASH, 93, Keyboard.KEY_RBRACKET,
            96, Keyboard.KEY_GRAVE,
            Input.KEY_ESCAPE, Keyboard.KEY_ESCAPE, Input.KEY_ENTER, Keyboard.KEY_RETURN, Input.KEY_TAB, Keyboard.KEY_TAB,
            Input.KEY_BACKSPACE, Keyboard.KEY_BACK, Input.KEY_INSERT, Keyboard.KEY_INSERT, Input.KEY_DELETE, Keyboard.KEY_DELETE,
            Input.KEY_RIGHT, Keyboard.KEY_RIGHT, Input.KEY_LEFT, Keyboard.KEY_LEFT, Input.KEY_DOWN, Keyboard.KEY_DOWN,
            Input.KEY_UP, Keyboard.KEY_UP, 266, Keyboard.KEY_PRIOR, 267, Keyboard.KEY_NEXT,
            Input.KEY_HOME, Keyboard.KEY_HOME, Input.KEY_END, Keyboard.KEY_END,
            280, Keyboard.KEY_CAPITAL, 281, Keyboard.KEY_SCROLL, 282, Keyboard.KEY_NUMLOCK,
            283, Keyboard.KEY_SYSRQ, 284, Keyboard.KEY_PAUSE,
            290, Keyboard.KEY_F1, 291, Keyboard.KEY_F2, 292, Keyboard.KEY_F3, 293, Keyboard.KEY_F4,
            294, Keyboard.KEY_F5, 295, Keyboard.KEY_F6, 296, Keyboard.KEY_F7, 297, Keyboard.KEY_F8,
            298, Keyboard.KEY_F9, 299, Keyboard.KEY_F10, 300, Keyboard.KEY_F11, 301, Keyboard.KEY_F12,
            320, Keyboard.KEY_NUMPAD0, 321, Keyboard.KEY_NUMPAD1, 322, Keyboard.KEY_NUMPAD2,
            323, Keyboard.KEY_NUMPAD3, 324, Keyboard.KEY_NUMPAD4, 325, Keyboard.KEY_NUMPAD5,
            326, Keyboard.KEY_NUMPAD6, 327, Keyboard.KEY_NUMPAD7, 328, Keyboard.KEY_NUMPAD8,
            329, Keyboard.KEY_NUMPAD9, 330, Keyboard.KEY_DECIMAL, 331, Keyboard.KEY_DIVIDE,
            332, Keyboard.KEY_MULTIPLY, 333, Keyboard.KEY_SUBTRACT, 334, Keyboard.KEY_ADD,
            335, Keyboard.KEY_NUMPADENTER, 336, Keyboard.KEY_NUMPADEQUALS,
            Input.KEY_LEFT_SHIFT, Keyboard.KEY_LSHIFT, Input.KEY_LEFT_CONTROL, Keyboard.KEY_LCONTROL,
            342, Keyboard.KEY_LMENU, 343, Keyboard.KEY_LMETA,
            Input.KEY_RIGHT_SHIFT, Keyboard.KEY_RSHIFT, Input.KEY_RIGHT_CONTROL, Keyboard.KEY_RCONTROL,
            346, Keyboard.KEY_RMENU, 347, Keyboard.KEY_RMETA, 348, Keyboard.KEY_APPS,
        };
        for (int index = 0; index < pairs.length; index += 2) {
            toLwjgl[pairs[index]] = pairs[index + 1];
            toGlfw[pairs[index + 1]] = pairs[index];
        }
    }

    private LwjglKeys() {}

    /** GLFW code for an LWJGL key, or 0 for one GLFW has no name for. */
    public static int glfw(int lwjglKey) {
        return lwjglKey > 0 && lwjglKey < toGlfw.length ? toGlfw[lwjglKey] : 0;
    }

    /** LWJGL code for a GLFW key, or 0 for one LWJGL has no key for. */
    public static int lwjgl(int glfwKey) {
        return glfwKey > 0 && glfwKey < toLwjgl.length ? toLwjgl[glfwKey] : 0;
    }
}
