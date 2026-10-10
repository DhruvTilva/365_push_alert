package androidx.test.runner.intent;
public final class IntentStubberRegistry {
    public static boolean isLoaded() { return false; }
    public static IntentStubber getInstance() { throw new IllegalStateException("not loaded"); }
}
