package dev.slinky.plugin;

import dev.slinky.SlinkyMod;
import dev.slinky.api.PluginContext;
import dev.slinky.api.SlinkyPlugin;
import dev.slinky.inject.Platform;
import dev.slinky.module.Module;
import dev.slinky.module.ModuleManager;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads prebuilt plugin jars from the plugin directory.
 *
 * <p>Every jar that contains {@code META-INF/services/dev.slinky.api.SlinkyPlugin}
 * is opened with the class loader that owns the mod, so plugin modules see the
 * identical game, packet and native surface as the built-in modules. A jar is
 * selected when its name ends in {@code -101.jar}, {@code -102.jar} or
 * {@code -103.jar} matching the detected namespace, or when its manifest
 * declares {@code Slinky-Namespace}; jars without either marker are loaded in
 * every namespace.</p>
 */
public final class PluginLoader {
   private static final String SERVICE = "META-INF/services/" + SlinkyPlugin.class.getName();
   private static final Pattern NAMESPACE_SUFFIX = Pattern.compile(".*-(101|102|103)\\.jar$");

   private PluginLoader() {
   }

   public static int load(ModuleManager manager) {
      Path pluginsDir = SlinkyMod.slinkyDirectory().resolve("plugins");
      List<Path> directories = new ArrayList<>();
      String payload = Platform.getPayloadPath();
      if (payload != null) {
         Path parent = Paths.get(payload).toAbsolutePath().getParent();
         if (parent != null) {
            directories.add(parent);
         }
      }
      directories.add(pluginsDir);

      Set<Path> seen = new LinkedHashSet<>();
      List<Path> jars = new ArrayList<>();
      for (Path directory : directories) {
         if (!Files.isDirectory(directory)) {
            continue;
         }
         try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.jar")) {
            for (Path path : stream) {
               Path jar = path.toAbsolutePath().normalize();
               if (seen.add(jar) && accepts(jar)) {
                  jars.add(jar);
               }
            }
         } catch (IOException error) {
            Platform.log("Could not scan plugin directory " + directory, error);
         }
      }
      Collections.sort(jars);

      int loaded = 0;
      for (Path jar : jars) {
         loaded += loadJar(jar, pluginsDir, manager);
      }
      return loaded;
   }

   private static int loadJar(Path jar, Path pluginsDir, ModuleManager manager) {
      ClassLoader previous = Thread.currentThread().getContextClassLoader();
      int loaded = 0;
      try {
         URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, Module.class.getClassLoader());
         Thread.currentThread().setContextClassLoader(loader);
         ServiceLoader<SlinkyPlugin> service = ServiceLoader.load(SlinkyPlugin.class, loader);

         for (SlinkyPlugin plugin : service) {
            String id = plugin.getClass().getName();
            try {
               plugin.onLoad(new PluginContext(id, pluginsDir, manager, message -> Platform.log("[plugin] " + message)));
               loaded++;
            } catch (Throwable error) {
               Platform.log("Plugin " + id + " failed to load", error);
            }
         }
      } catch (Throwable error) {
         Platform.log("Could not load plugin jar " + jar, error);
      } finally {
         Thread.currentThread().setContextClassLoader(previous);
      }

      if (loaded > 0) {
         Platform.log("Loaded " + loaded + " plugin module(s) from " + jar);
      }
      return loaded;
   }

   private static boolean accepts(Path jar) {
      Integer id = namespaceId(jar.getFileName().toString());
      if (id != null && id.intValue() != resourceId()) {
         return false;
      }
      if (!hasService(jar)) {
         return false;
      }
      if (id != null) {
         return true;
      }
      String declared = manifestNamespace(jar);
      return declared == null || Platform.getNamespace().id.equalsIgnoreCase(declared);
   }

   private static boolean hasService(Path jar) {
      try (JarFile file = new JarFile(jar.toFile())) {
         return file.getJarEntry(SERVICE) != null;
      } catch (IOException error) {
         return false;
      }
   }

   private static String manifestNamespace(Path jar) {
      try (JarFile file = new JarFile(jar.toFile())) {
         if (file.getManifest() == null) {
            return null;
         }
         return file.getManifest().getMainAttributes().getValue("Slinky-Namespace");
      } catch (IOException error) {
         return null;
      }
   }

   private static Integer namespaceId(String name) {
      Matcher matcher = NAMESPACE_SUFFIX.matcher(name);
      return matcher.matches() ? Integer.valueOf(matcher.group(1)) : null;
   }

   private static int resourceId() {
      switch (Platform.getNamespace()) {
         case SRG:
            return 102;
         case OFFICIAL:
            return 103;
         case NAMED:
         default:
            return 101;
      }
   }
}
