package cobble.util.font;

public interface TextTopOffsetProvider {
    default int getTextTopOffset() {
        return ComponentTextMetrics.top();
    }
}
