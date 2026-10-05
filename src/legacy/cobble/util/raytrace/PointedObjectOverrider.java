package cobble.util.raytrace;

public interface PointedObjectOverrider {
    boolean shouldOverridePointedObject();

    void applyPointedObjectOverride(float var1);
}
