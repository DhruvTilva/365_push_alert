package androidx.test.espresso;
import java.util.*;
public final class IdlingRegistry {
    private static final IdlingRegistry INSTANCE = new IdlingRegistry();
    public static IdlingRegistry getInstance() { return INSTANCE; }
    public Collection<android.os.Looper> getLoopers() { return Collections.emptyList(); }
    public Collection<IdlingResource> getResources() { return Collections.emptyList(); }
}
