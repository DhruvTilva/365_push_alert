package androidx.test.platform.app;
import android.app.Instrumentation;
import android.os.Bundle;
/** Test-only stub: the real class is on Google Maven, which the build environment cannot reach. */
public final class InstrumentationRegistry {
    private static Instrumentation instrumentation;
    private static Bundle arguments;
    private InstrumentationRegistry() {}
    public static void registerInstance(Instrumentation i, Bundle args) { instrumentation = i; arguments = args; }
    public static Instrumentation getInstrumentation() {
        if (instrumentation == null) throw new IllegalStateException("No instrumentation registered");
        return instrumentation;
    }
    public static Bundle getArguments() { return arguments; }
}
