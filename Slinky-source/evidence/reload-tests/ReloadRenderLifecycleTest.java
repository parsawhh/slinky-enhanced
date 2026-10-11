package dev.slinky.client.hook;

import dev.slinky.client.ClientContext;
import dev.slinky.client.render.RenderAccess;
import dev.slinky.module.Module;
import dev.slinky.module.ModuleCategory;
import dev.slinky.module.ModuleManager;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import net.minecraft.client.Minecraft;

public class ReloadRenderLifecycleTest {
   static class TestModule extends Module {
      TestModule() { super("test", "Test", "test", ModuleCategory.values()[0]); }
      public boolean listens(int event) { return event == 10; }
   }
   static ModuleManager manager() {
      ModuleManager manager = new ModuleManager(new ClientContext(new Minecraft()));
      TestModule module = new TestModule();
      manager.register(module);
      manager.setEnabled(module, true, false);
      manager.applyPendingChanges();
      return manager;
   }
   static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
   public static void main(String[] args) throws Exception {
      java.util.concurrent.atomic.AtomicInteger ticks = new java.util.concurrent.atomic.AtomicInteger();
      java.util.function.DoubleSupplier previousClock = RenderAccess.bindClock(() -> 100.0 + ticks.incrementAndGet());
      ModuleManager old = manager();
      RenderHooks.install(old);
      RenderHooks.beforeDisplay();
      RenderHooks.beforeDisplay();
      require(ForgeUiSurface.lastDrawn == old, "first draw must use first manager");
      Field rendersField = RenderHooks.class.getDeclaredField("livingRenders"); rendersField.setAccessible(true);
      @SuppressWarnings("unchecked") ArrayDeque<Boolean> renders = (ArrayDeque<Boolean>)rendersField.get(null);
      renders.add(true);
      RenderHooks.install(null);
      require(ForgeUiSurface.closed == 0, "null detach must not close a GL surface");
      ModuleManager fresh = manager();
      RenderHooks.install(fresh);
      require(ForgeUiSurface.closed == 1, "client-thread install must close retained old surface");
      require(renders.isEmpty(), "reload must reset pending living render stack");
      RenderHooks.beforeDisplay();
      RenderHooks.beforeDisplay();
      require(ForgeUiSurface.lastDrawn == fresh, "first new draw must use replacement manager");
      RenderHooks.clear(); RenderHooks.install(null);
      RenderAccess.bindClock(previousClock);
      System.out.println("PASS: null detach leaves GL surface alone; fresh install closes old surface and draws with new manager.");
   }
}
