package com.nextgen.desktop.ui;

/**
 * Entry point for the packaged jar.
 *
 * <p>Cannot be {@link DesktopApp} itself: when a jar's {@code Main-Class} directly extends {@code
 * javafx.application.Application}, the {@code java} launcher requires JavaFX on the module path
 * before it will run it at all, which a plain {@code java -jar} on a shaded/fat jar does not provide
 * — it fails immediately with "JavaFX runtime components are missing", regardless of whether the
 * JavaFX jars are actually present on the classpath. A separate class that merely calls {@link
 * DesktopApp#main} sidesteps that check, since the launcher only inspects the class named in {@code
 * Main-Class}. {@code mvn javafx:run} (the javafx-maven-plugin goal) does not need this indirection —
 * it sets up the module path itself — so it keeps pointing at {@link DesktopApp} directly.
 */
public final class Launcher {
    private Launcher() {
    }

    public static void main(String[] args) {
        DesktopApp.main(args);
    }
}
