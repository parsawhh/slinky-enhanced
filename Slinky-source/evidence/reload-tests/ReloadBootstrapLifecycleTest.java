import dev.slinky.SlinkyMod;
import dev.slinky.client.ClientEventBridge;
import dev.slinky.client.hook.GameHooks;
import dev.slinky.inject.Bootstrap;
import dev.slinky.inject.transform.TransformerEngine;
import dev.slinky.module.ModuleManager;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;

public class ReloadBootstrapLifecycleTest {
    static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static ModuleManager manager() throws Exception { Field field = GameHooks.class.getDeclaredField("modules"); field.setAccessible(true); return (ModuleManager)field.get(null); }
    static void reset() { ClientEventBridge.install(null); GameHooks.install(null); System.clearProperty("slinky.injected"); System.clearProperty("slinky.patches.installed"); }
    public static void main(String[] args) throws Throwable {
        reset();
        Bootstrap.start("named", "test.jar");
        ModuleManager first = manager();
        require(first != null && TransformerEngine.applications == 1 && SlinkyMod.created == 1, "fresh start must initialize and patch once");
        System.clearProperty("slinky.injected");
        Bootstrap.start("named", "test.jar");
        require(manager() == first && SlinkyMod.created == 1, "active session must skip even with missing marker");
        first.shutdown();
        Bootstrap.start("named", "test.jar");
        require(manager() != null && manager() != first && SlinkyMod.created == 2, "unload must permit a fresh manager");
        require(TransformerEngine.applications == 1, "reload must reuse existing patches");

        // Simulate an old build's unload: shutdown flag set, but hooks and startup marker left behind.
        ModuleManager previous = manager();
        Field shutdown = ModuleManager.class.getDeclaredField("shutdown"); shutdown.setAccessible(true);
        ((AtomicBoolean)shutdown.get(previous)).set(true);
        System.clearProperty("slinky.patches.installed");
        Bootstrap.start("named", "test.jar");
        require(manager() != previous && SlinkyMod.created == 3, "legacy unloaded manager must be replaced");
        require(TransformerEngine.applications == 1, "legacy hooks must not be installed twice");
        manager().shutdown();

        reset(); SlinkyMod.failInitialize = true;
        boolean failed = false;
        try { Bootstrap.start("named", "test.jar"); } catch (Throwable expected) { failed = true; }
        require(failed && manager() == null && System.getProperty("slinky.injected") == null, "startup failure must clean manager and marker");
        SlinkyMod.failInitialize = false;

        reset(); TransformerEngine.failures = 2; failed = false;
        try { Bootstrap.start("named", "test.jar"); } catch (Throwable expected) { failed = true; }
        require(failed && manager() == null && System.getProperty("slinky.injected") == null, "patch failure must be observable and clean startup");
        TransformerEngine.failures = 0;

        reset(); failed = false;
        try { Bootstrap.start("official", "test.jar"); } catch (Throwable expected) { failed = true; }
        require(failed && System.getProperty("slinky.injected") == null, "missing required mappings must clear startup marker");
        reset(); net.minecraft.client.Minecraft.missing = true; failed = false;
        try { Bootstrap.start("named", "test.jar"); } catch (Throwable expected) { failed = true; }
        require(failed && System.getProperty("slinky.injected") == null, "missing Minecraft must clear startup marker");
        System.out.println("PASS: initial start, active duplicate, reload, legacy unload migration, initialization failure, patch failure, missing mappings, missing game.");
    }
}