package cobble.v1_21.compat;

import net.minecraft.client.input.Input;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec2f;

/**
 * 1.8.9's MovementInput on 1.21.11's Input: the strafe and forward amounts
 * are its movement vector, the jump and sneak flags part of its key record.
 */
public final class MovementInputShim {
    private MovementInputShim() {}

    public static float moveForward(Input input) {
        return input.movementVector.y;
    }

    public static float moveStrafe(Input input) {
        return input.movementVector.x;
    }

    public static void moveForward(Input input, float value) {
        input.movementVector = new Vec2f(input.movementVector.x, value);
    }

    public static void moveStrafe(Input input, float value) {
        input.movementVector = new Vec2f(value, input.movementVector.y);
    }

    public static boolean jump(Input input) {
        return input.playerInput.jump();
    }

    public static boolean sneak(Input input) {
        return input.playerInput.sneak();
    }

    public static void jump(Input input, boolean jump) {
        PlayerInput keys = input.playerInput;
        input.playerInput = new PlayerInput(keys.forward(), keys.backward(), keys.left(), keys.right(), jump,
                keys.sneak(), keys.sprint());
    }

    public static void sneak(Input input, boolean sneak) {
        PlayerInput keys = input.playerInput;
        input.playerInput = new PlayerInput(keys.forward(), keys.backward(), keys.left(), keys.right(), keys.jump(),
                sneak, keys.sprint());
    }
}
