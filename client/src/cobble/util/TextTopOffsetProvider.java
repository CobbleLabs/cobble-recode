package cobble.util;

public interface TextTopOffsetProvider {
    default int getTextTopOffset() {
        return ComponentTextMetrics.top();
    }
}
