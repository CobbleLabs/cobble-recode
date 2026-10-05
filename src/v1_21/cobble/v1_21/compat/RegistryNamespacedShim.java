package cobble.v1_21.compat;

import net.minecraft.registry.Registry;

/** 1.8.9's RegistryNamespaced on 1.21.11's Registry, which names things by Identifier as 1.8 did by ResourceLocation. */
public final class RegistryNamespacedShim {
    private RegistryNamespacedShim() {}

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Object getNameForObject(Registry registry, Object value) {
        return registry.getId(value);
    }
}
