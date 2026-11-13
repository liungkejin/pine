package top.canyie.pine.regression;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import top.canyie.pine.Pine;
import top.canyie.pine.PineConfig;
import top.canyie.pine.callback.MethodHook;

/** Exercises the generated DEX through ART, including its verifier, calling convention and GC. */
public final class TypedBridgeTest {
    public static boolean bool(boolean x) { return x; }
    public static byte octet(byte x) { return x; }
    public static char character(char x) { return x; }
    public static short small(short x) { return x; }
    public static int integer(int x) { return x; }
    public static long wide(long x) { return x; }
    public static float single(float x) { return x; }
    public static double decimal(double x) { return x; }
    public static Object reference(Object x) { return x; }
    public static Object noArguments() { return TOKEN; }
    public static void empty() { voidCalls++; }
    private static final Object TOKEN = new Object();
    private static int voidCalls;
    private static final class PrivateType { }
    public static PrivateType[] array(PrivateType[] values) { return values; }
    public static Object mixed(boolean a, byte b, char c, short d, int e, long f,
                               float g, double h, Object i, double j, long k, Object l,
                               float m, double n, double o, double p, double q,
                               double r, double s, Object t) { return t; }
    public static Object throwsError(Object value) { throw ERROR; }
    private static final RuntimeException ERROR = new RuntimeException("expected target exception");
    public static final class Constructed {
        final Object value;
        public Constructed(Object value) { this.value = value; }
    }
    public interface Service { Object call(Object value); }
    public static native Object nativeEcho(Object value);

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static MethodHook checking(Object receiver, Object[] arguments, Object result,
                                       boolean skip, int[] calls) {
        return new MethodHook() {
            @Override public void beforeCall(Pine.CallFrame frame) {
                try {
                    check(frame.thisObject == receiver, "receiver identity");
                    check(Arrays.equals(arguments, frame.args), "arguments " + frame.method);
                    // Force collection after entry and before original/return processing.
                    Runtime.getRuntime().gc();
                    calls[0]++;
                    if (skip) frame.setResult(result);
                } catch (Throwable error) {
                    frame.setThrowable(error); // Pine intentionally catches callback exceptions.
                }
            }
        };
    }

    private static void roundTrip(String name, Class<?> type, Object value) throws Exception {
        Method target = TypedBridgeTest.class.getDeclaredMethod(name, type);
        int[] calls = {0};
        MethodHook.Unhook hook = Pine.hook(target, checking(null, new Object[] {value}, value, true, calls));
        try {
            check(value.equals(target.invoke(null, value)), name + " replacement return");
            Pine.HookRecord record = Pine.getHookRecord(Pine.getArtMethod(target));
            check(record.bridge.getParameterTypes()[0] == (type.isPrimitive() ? type : Object.class),
                    "reference parameter was erased to integer");
        } finally { hook.unhook(); }
        hook = Pine.hook(target, checking(null, new Object[] {value}, null, false, calls));
        try { check(value.equals(target.invoke(null, value)), name + " original return"); }
        finally { hook.unhook(); }
        check(value.equals(target.invoke(null, value)), name + " unhook");
        check(calls[0] == 2, name + " callback count");
    }

    public static String run(String directory, boolean inline, boolean jit) throws Exception {
        PineConfig.debug = false;
        PineConfig.debuggable = false;
        PineConfig.libLoader = () -> System.load(directory + "/libpine.so");
        Pine.ensureInitialized();
        System.load(directory + "/libpine_init_probe.so");
        if (inline) Pine.setHookMode(Pine.HookMode.INLINE);
        if (jit) Pine.setJitCompilationAllowed(true, true);
        roundTrip("bool", boolean.class, true);
        roundTrip("octet", byte.class, (byte) -123);
        roundTrip("character", char.class, '\ufffe');
        roundTrip("small", short.class, (short) -30001);
        roundTrip("integer", int.class, 0x87654321);
        roundTrip("wide", long.class, 0x9876543212345678L);
        roundTrip("single", float.class, -3.125f);
        roundTrip("decimal", double.class, 98765.125d);
        roundTrip("reference", Object.class, TOKEN);
        roundTrip("array", PrivateType[].class, new PrivateType[] {new PrivateType(), null});

        Method empty = TypedBridgeTest.class.getDeclaredMethod("empty");
        int[] calls = {0};
        MethodHook.Unhook hook = Pine.hook(empty, checking(null, new Object[0], null, false, calls));
        try { check(empty.invoke(null) == null && voidCalls == 1, "void original"); }
        finally { hook.unhook(); }
        hook = Pine.hook(empty, checking(null, new Object[0], null, true, calls));
        try { check(empty.invoke(null) == null && voidCalls == 1, "void replacement"); }
        finally { hook.unhook(); }
        check(calls[0] == 2, "void callbacks");

        Method noArgs = TypedBridgeTest.class.getDeclaredMethod("noArguments");
        calls[0] = 0;
        hook = Pine.hook(noArgs, checking(null, new Object[0], null, true, calls));
        try { check(noArgs.invoke(null) == null, "null replacement with zero arguments"); }
        finally { hook.unhook(); }
        check(noArgs.invoke(null) == TOKEN && calls[0] == 1, "zero arguments original");

        Method mixed = TypedBridgeTest.class.getDeclaredMethod("mixed", boolean.class, byte.class,
                char.class, short.class, int.class, long.class, float.class, double.class, Object.class,
                double.class, long.class, Object.class, float.class, double.class, double.class,
                double.class, double.class, double.class, double.class, Object.class);
        Object[] values = {true, (byte) -9, '\ufffe', (short) -1234, -345678, Long.MIN_VALUE,
                Float.intBitsToFloat(0x80000000), Double.NaN, TOKEN, 1.25d, Long.MAX_VALUE, null,
                Float.NaN, 2.5d, -0.0d, Double.POSITIVE_INFINITY, -19d, 33d, 64d, TOKEN};
        calls[0] = 0;
        hook = Pine.hook(mixed, checking(null, values, null, false, calls));
        try { check(mixed.invoke(null, values) == TOKEN && calls[0] == 1, "mixed register/stack arguments"); }
        finally { hook.unhook(); }

        Constructor<Constructed> ctor = Constructed.class.getDeclaredConstructor(Object.class);
        int[] constructed = {0};
        hook = Pine.hook(ctor, new MethodHook() {
            @Override public void beforeCall(Pine.CallFrame frame) {
                if (!(frame.thisObject instanceof Constructed) || frame.args[0] != TOKEN) {
                    frame.setThrowable(new AssertionError("constructor receiver/argument"));
                } else { constructed[0]++; }
            }
        });
        try { check(ctor.newInstance(TOKEN).value == TOKEN && constructed[0] == 1, "constructor original"); }
        finally { hook.unhook(); }

        Method throwing = TypedBridgeTest.class.getDeclaredMethod("throwsError", Object.class);
        calls[0] = 0;
        // This fork deliberately swallows backup exceptions in callBackupMethod. Preserve
        // that existing policy; verify exceptions explicitly returned by the callback instead.
        hook = Pine.hook(throwing, new MethodHook() {
            @Override public void beforeCall(Pine.CallFrame frame) {
                calls[0]++;
                frame.setThrowable(ERROR);
            }
        });
        try {
            try { throwing.invoke(null, TOKEN); throw new AssertionError("missing callback exception"); }
            catch (InvocationTargetException e) { check(e.getCause() == ERROR, "callback exception identity"); }
            check(calls[0] == 1, "exception callback count");
        } finally { hook.unhook(); }

        Service proxy = (Service) Proxy.newProxyInstance(Service.class.getClassLoader(),
                new Class<?>[] {Service.class}, (instance, method, args) -> args[0]);
        Method proxyMethod = proxy.getClass().getDeclaredMethod("call", Object.class);
        calls[0] = 0;
        hook = Pine.hook(proxyMethod, checking(proxy, new Object[] {TOKEN}, null, false, calls));
        try { check(proxyMethod.invoke(proxy, TOKEN) == TOKEN && calls[0] == 1, "proxy original"); }
        finally { hook.unhook(); }

        // Native targets use the same typed entry but retain their JNI backup calling convention.
        Method nativeMethod = TypedBridgeTest.class.getDeclaredMethod("nativeEcho", Object.class);
        calls[0] = 0;
        hook = Pine.hook(nativeMethod, checking(null, new Object[] {TOKEN}, null, false, calls));
        try { check(nativeMethod.invoke(null, TOKEN) == TOKEN && calls[0] == 1, "native original"); }
        finally { hook.unhook(); }
        return "17 signature/behavior cases; inline=" + inline + " jit=" + jit;
    }
}
