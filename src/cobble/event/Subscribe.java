package cobble.event;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Subscribe {
    Priority priority() default Priority.NORMAL;

    /** Listeners run highest first. */
    enum Priority { HIGHEST, HIGH, NORMAL, LOW, LOWEST }
}
