package dev.slinky.api;

import dev.slinky.module.Module;
import dev.slinky.module.ModuleCategory;
import dev.slinky.setting.BooleanSetting;
import dev.slinky.setting.ChoiceSetting;
import dev.slinky.setting.ColorSetting;
import dev.slinky.setting.NumberSetting;
import dev.slinky.setting.Setting;
import dev.slinky.setting.StringListSetting;
import java.util.ArrayList;
import java.util.List;

/**
 * Base class for modules that are supplied by drop-in plugins.
 *
 * <p>Instances are created by the plugin loader and registered with the normal
 * {@code ModuleManager}, so they take part in ticking, events, key bindings and
 * configuration exactly like the built-in modules. Only the recovered evidence
 * metadata (UI layout, descriptor flags, widget schema) is absent, and the GUI
 * falls back to a flat settings screen for these modules.</p>
 *
 * <p>Everything on this class intentionally avoids Minecraft types in its
 * signatures. That keeps plugin bytecode free of obfuscated names, so a plugin
 * compiled against this API loads unchanged on notch, SRG and MCP runtimes.</p>
 */
public abstract class PluginModule extends Module {
   protected PluginModule(String id, String name, String description, ModuleCategory category) {
      super(id, name, description, category);
   }

   protected final BooleanSetting bool(String key, String label, boolean initialValue) {
      return this.add(new BooleanSetting(key, label, initialValue));
   }

   protected final NumberSetting number(String key, String label, double initialValue, double minimum, double maximum, double step) {
      return this.add(new NumberSetting(key, label, initialValue, minimum, maximum, step));
   }

   protected final ChoiceSetting choice(String key, String label, int initialValue, String... options) {
      return this.add(new ChoiceSetting(key, label, initialValue, options));
   }

   protected final StringListSetting list(String key, String label, List<String> initialValue) {
      return this.add(new StringListSetting(key, label, new ArrayList<>(initialValue)));
   }

   protected final ColorSetting colorSetting(String key, String label) {
      return this.add(new ColorSetting(key, label));
   }

   protected final boolean flag(String key) {
      return ((BooleanSetting)this.findSetting(key)).isEnabled();
   }

   protected final double numberValue(String key) {
      return ((NumberSetting)this.findSetting(key)).doubleValue();
   }

   protected final int choiceIndex(String key) {
      return ((ChoiceSetting)this.findSetting(key)).index();
   }

   protected final List<String> listValue(String key) {
      return ((StringListSetting)this.findSetting(key)).get();
   }

   @Override
   public int metadataCategory() {
      switch (this.getCategory()) {
         case COMBAT:
            return 0;
         case MOVEMENT:
            return 1;
         case RENDER:
            return 2;
         case BLOCKS:
            return 3;
         case UTILITY:
         case WORLD:
         case UNKNOWN:
         default:
            return 4;
      }
   }

   @Override
   public int descriptorFlags() {
      return 0;
   }
}
