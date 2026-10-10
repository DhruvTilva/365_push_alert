package androidx.test.internal.runner.lifecycle;
import android.app.Application;
import androidx.test.runner.lifecycle.*;
import java.util.*;
public class ApplicationLifecycleMonitorImpl implements ApplicationLifecycleMonitor {
    private final List<ApplicationLifecycleCallback> callbacks = new ArrayList<>();
    public synchronized void addLifecycleCallback(ApplicationLifecycleCallback c) { callbacks.add(c); }
    public synchronized void removeLifecycleCallback(ApplicationLifecycleCallback c) { callbacks.remove(c); }
    public void signalLifecycleChange(Application app, ApplicationStage stage) {
        List<ApplicationLifecycleCallback> copy;
        synchronized (this) { copy = new ArrayList<>(callbacks); }
        for (ApplicationLifecycleCallback c : copy) c.onApplicationLifecycleChanged(app, stage);
    }
}
