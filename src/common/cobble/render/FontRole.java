package cobble.render;

public enum FontRole {
    REGULAR("Geist-Regular"),
    MEDIUM("Geist-Medium"),
    SEMI_BOLD("Geist-SemiBold"),
    BOLD("Geist-Bold"),
    MONO("JetBrainsMono-Medium"),
    MONO_BOLD("JetBrainsMono-Bold");

    final String file;

    FontRole(String file) {
        this.file = file;
    }
}
