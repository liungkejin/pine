package top.canyie.pine.regression;

import java.lang.reflect.Method;
import top.canyie.pine.Pine;
import top.canyie.pine.PineConfig;
import top.canyie.pine.callback.MethodHook;

public class InitializationTest {
    private static native void mapArtFile(int mode);

    public static int target(int value) { return value + 1; }

    public static void main(String[] args) throws Exception {
        String directory = args[0];
        String scenario = args[1];
        System.load(directory + "/libpine_init_probe.so");
        if (scenario.equals("readonly_alias") || scenario.equals("executable_alias") || scenario.equals("header_alias")) {
            mapArtFile(scenario.equals("header_alias") ? 2 : scenario.equals("executable_alias") ? 1 : 0);
        }
        PineConfig.debug = true;
        PineConfig.debuggable = false;
        // Isolate Runtime initialization from hidden-API patching for the alias
        // cases. A separate case exercises the production default configuration.
        PineConfig.disableHiddenApiPolicy = scenario.equals("default_hooks");
        PineConfig.disableHiddenApiPolicyForPlatformDomain = scenario.equals("default_hooks");
        PineConfig.libLoader = () -> System.load(directory + "/libpine.so");
        Pine.ensureInitialized();
        Pine.ensureInitialized();
        if (!Pine.isInitialized()) throw new AssertionError("Pine was not initialized");
        Method method = InitializationTest.class.getDeclaredMethod("target", int.class);
        MethodHook.Unhook unhook = Pine.hook(method, new MethodHook() {
            @Override public void beforeCall(Pine.CallFrame frame) {
                frame.setResult(42);
            }
        });
        if (!method.invoke(null, 1).equals(42)) throw new AssertionError("hook did not run");
        unhook.unhook();
        if (!method.invoke(null, 1).equals(2)) throw new AssertionError("unhook did not restore result");
        System.out.println("PASS initialization " + scenario);
    }
}
