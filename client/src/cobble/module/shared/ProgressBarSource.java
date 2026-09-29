package cobble.module.shared;

public interface ProgressBarSource {
    boolean isProgressActive();

    float getProgressFraction();

    String getProgressLabel();
}
