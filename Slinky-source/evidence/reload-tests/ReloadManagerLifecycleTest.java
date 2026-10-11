import dev.slinky.client.ClientContext;
import dev.slinky.client.ClientEventBridge;
import dev.slinky.client.hook.GameHooks;
import dev.slinky.module.Module;
import dev.slinky.module.ModuleCategory;
import dev.slinky.module.ModuleManager;
import java.lang.reflect.Field;

public class ReloadManagerLifecycleTest {
    static class TestModule extends Module {
        boolean failWorldUnload;
        TestModule() { super("test", "Test", "test", ModuleCategory.values()[0]); }
        @Override public void onWorldUnload(ClientContext context) {
            if (failWorldUnload) throw new IllegalStateException("injected world cleanup failure");
        }
    }
    static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    static Object field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }
    static void test(boolean failSave, boolean failWorldUnload) throws Exception {
        ModuleManager manager = new ModuleManager(new ClientContext(null));
        TestModule module = new TestModule();
        module.failWorldUnload = failWorldUnload;
        manager.register(module);
        manager.setEnabled(module, true, false);
        manager.applyPendingChanges();
        require(module.isEnabled(), "test must begin with enabled module");
        manager.setPersistence(() -> { if (failSave) throw new IllegalStateException("injected save failure"); });
        GameHooks.install(manager);
        ClientEventBridge.install(new ClientEventBridge(manager, null, null));
        System.setProperty("slinky.injected", "true");
        boolean threw = false;
        try { manager.shutdown(); } catch (IllegalStateException expected) { threw = true; }
        require(threw == (failSave || failWorldUnload), "failure should be observable");
        require(!module.isEnabled(), "shutdown must disable module after any failure");
        require(field(GameHooks.class, "modules") == null, "shutdown must detach gameplay hooks");
        require(field(ClientEventBridge.class, "instance") == null, "shutdown must detach input bridge");
        require(System.getProperty("slinky.injected") == null, "shutdown must clear injected marker");
        manager.shutdown();
        require(!manager.requestEnabled(module, true), "old manager must remain disabled");
    }
    public static void main(String[] args) throws Exception {
        test(false, false);
        test(true, false);
        test(false, true);
        System.out.println("PASS: normal unload, failed-save unload, and failed-world-cleanup unload all disable modules and detach hooks.");
    }
}
