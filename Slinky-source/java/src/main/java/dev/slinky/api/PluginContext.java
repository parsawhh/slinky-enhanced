package dev.slinky.api;

import dev.slinky.module.Module;
import dev.slinky.module.ModuleManager;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Registration and logging surface handed to each plugin while it loads. */
public final class PluginContext {
   private final String id;
   private final Path directory;
   private final ModuleManager manager;
   private final Consumer<String> logger;

   public PluginContext(String id, Path directory, ModuleManager manager, Consumer<String> logger) {
      this.id = id;
      this.directory = directory;
      this.manager = manager;
      this.logger = logger;
   }

   public String id() {
      return this.id;
   }

   public Path directory() {
      return this.directory;
   }

   public ModuleManager modules() {
      return this.manager;
   }

   /** Registers a module with the client. Duplicate ids are rejected. */
   public Module register(Module module) {
      this.manager.register(module);
      return module;
   }

   public void log(String message) {
      this.logger.accept(message);
   }
}
