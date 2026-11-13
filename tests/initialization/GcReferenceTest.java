package top.canyie.pine.regression;

import android.os.Debug;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import top.canyie.pine.Pine;
import top.canyie.pine.PineConfig;
import top.canyie.pine.callback.MethodHook;

public final class GcReferenceTest {
    private static final ThreadLocal<Object[]> expected = new ThreadLocal<>();
    public Object target(Object a, Object b, long wide, double fp, Object c, Object d,
                         Object e, Object f, Object g, Object h) { return a; }

    public static String run(String directory, String scenario) throws Exception {
        PineConfig.debug = false;
        PineConfig.debuggable = false;
        PineConfig.libLoader = () -> System.load(directory + "/libpine.so");
        Pine.ensureInitialized();
        if (scenario.equals("gc_inline")) Pine.setHookMode(Pine.HookMode.INLINE);
        if (scenario.equals("gc_jit")) Pine.setJitCompilationAllowed(true, true);
        Method method = GcReferenceTest.class.getDeclaredMethod("target", Object.class, Object.class,
                long.class, double.class, Object.class, Object.class, Object.class, Object.class,
                Object.class, Object.class);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        MethodHook.Unhook unhook = Pine.hook(method, new MethodHook() {
            @Override public void beforeCall(Pine.CallFrame frame) {
                try {
                    Object[] values = expected.get();
                    if (frame.thisObject != values[0]) throw new AssertionError("receiver changed during GC");
                    for (int i = 0; i < frame.args.length; ++i) {
                        Object wanted = values[i + 1];
                        if (i == 2 || i == 3) {
                            if (!wanted.equals(frame.args[i])) throw new AssertionError("primitive argument corrupted");
                        } else if (wanted != frame.args[i]) {
                            throw new AssertionError("reference argument " + i + " changed during GC");
                        }
                    }
                    calls.incrementAndGet();
                    // Isolate argument conversion from backup-method/class lifecycle bugs.
                    if (!scenario.equals("gc_original")) frame.setResult(values[1]);
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                    frame.setThrowable(error);
                }
            }
        });
        AtomicBoolean stop = new AtomicBoolean();
        long beforeGc = Long.parseLong(Debug.getRuntimeStat("art.gc.gc-count"));
        Thread[] workers = new Thread[4];
        for (int w = 0; w < workers.length; ++w) {
            workers[w] = new Thread(() -> {
                try {
                    while (!stop.get()) {
                        GcReferenceTest receiver = new GcReferenceTest();
                        Object[] values = {receiver, new Object(), new Object(), 0x1234567812345678L,
                                12.5d, new Object(), null, new Object(), new Object(), new Object(), new Object()};
                        expected.set(values);
                        Object result = method.invoke(receiver, values[1], values[2], values[3], values[4],
                                values[5], values[6], values[7], values[8], values[9], values[10]);
                        if (result != values[1]) throw new AssertionError("reference return corrupted");
                    }
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                    stop.set(true);
                } finally {
                    expected.remove();
                }
            }, "pine-gc-worker-" + w);
            workers[w].start();
        }
        try {
            for (int i = 0; i < 100 && !stop.get(); ++i) {
                byte[][] pressure = new byte[32][];
                for (int j = 0; j < pressure.length; ++j) pressure[j] = new byte[16384];
                Runtime.getRuntime().gc();
                Thread.sleep(10);
            }
        } finally {
            stop.set(true);
            for (Thread worker : workers) worker.join(10000);
            unhook.unhook();
        }
        for (Thread worker : workers) if (worker.isAlive()) throw new AssertionError("GC deadlock");
        if (failure.get() != null) throw new AssertionError("GC hook failed", failure.get());
        long collections = Long.parseLong(Debug.getRuntimeStat("art.gc.gc-count")) - beforeGc;
        if (collections < 20 || calls.get() < 1000) throw new AssertionError("insufficient GC/call coverage");
        return "GC collections=" + collections + " calls=" + calls.get() + " workers=4";
    }
}
