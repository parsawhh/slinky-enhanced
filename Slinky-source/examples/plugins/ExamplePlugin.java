package plugins.example;

import dev.slinky.api.PluginContext;
import dev.slinky.api.PluginModule;
import dev.slinky.api.SlinkyPlugin;
import dev.slinky.client.ClientContext;
import dev.slinky.client.SemanticEvent;
import dev.slinky.inject.NativeBridge;
import dev.slinky.module.ModuleCategory;

public final class ExamplePlugin implements SlinkyPlugin {
   @Override
   public void onLoad(PluginContext context) {
      context.log("Registering example module; native bridge " + NativeBridge.class.getName());
      context.register(new ExampleModule());
   }

   public static final class ExampleModule extends PluginModule {
      public ExampleModule() {
         super("example_auto_jump", "Example Auto Jump", "Drop-in example module", ModuleCategory.MOVEMENT);
         this.bool("only_ground", "Only On Ground", true);
      }

      @Override
      public boolean listens(int type) {
         return type == 17;
      }

      @Override
      public void onEvent(ClientContext context, SemanticEvent event) {
         if (event.type == 17 && context.ready() && context.player().onGround) {
            if (!this.flag("only_ground") || context.player().onGround) {
               context.player().jump();
            }
         }
      }
   }
}
