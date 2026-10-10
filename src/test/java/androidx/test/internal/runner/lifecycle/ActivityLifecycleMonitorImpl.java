package androidx.test.internal.runner.lifecycle;
import android.app.Activity;
import androidx.test.runner.lifecycle.*;
import java.util.*;
public class ActivityLifecycleMonitorImpl implements ActivityLifecycleMonitor {
    private final List<ActivityLifecycleCallback> callbacks = new ArrayList<>();
    private final Map<Activity, Stage> stages = new IdentityHashMap<>();
    public synchronized void addLifecycleCallback(ActivityLifecycleCallback c) { callbacks.add(c); }
    public synchronized void removeLifecycleCallback(ActivityLifecycleCallback c) { callbacks.remove(c); }
    public synchronized Stage getLifecycleStageOf(Activity a) { Stage s = stages.get(a); return s == null ? Stage.DESTROYED : s; }
    public synchronized Collection<Activity> getActivitiesInStage(Stage stage) {
        List<Activity> out = new ArrayList<>();
        for (Map.Entry<Activity, Stage> e : stages.entrySet()) if (e.getValue() == stage) out.add(e.getKey());
        return out;
    }
    public void signalLifecycleChange(Stage stage, Activity activity) {
        List<ActivityLifecycleCallback> copy;
        synchronized (this) { stages.put(activity, stage); copy = new ArrayList<>(callbacks); }
        for (ActivityLifecycleCallback c : copy) c.onActivityLifecycleChanged(activity, stage);
    }
}
