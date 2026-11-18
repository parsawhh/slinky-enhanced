import dev.slinky.client.ClientContext;
import dev.slinky.client.render.GuiAppearance;
import dev.slinky.config.ConfigStore;
import dev.slinky.inject.Mappings;
import dev.slinky.module.Module;
import dev.slinky.module.ModuleManager;
import dev.slinky.plugin.PluginLoader;
import dev.slinky.setting.Setting;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/** Offline end-to-end and property checks for the Slinky plugin pipeline. */
public final class PluginTestMain {
   private static int failures;

   public static void main(String[] args) throws Exception {
      switch (args[0]) {
         case "builder":
            PluginBuilder.build(Paths.get(args[1]), Paths.get(args[2]));
            break;
         case "load":
            load(args);
            break;
         case "mapping":
            mapping(args);
            break;
         default:
            throw new IllegalArgumentException("unknown command " + args[0]);
      }
      System.out.println(failures == 0 ? "RESULT PASS" : "RESULT FAIL " + failures);
      if (failures != 0) {
         System.exit(1);
      }
   }

   private static void check(boolean condition, String message) {
      if (!condition) {
         failures++;
         System.out.println("FAIL " + message);
      }
   }

   // ------------------------------------------------------------------ load

   private static void load(String[] args) throws Exception {
      int id = Integer.parseInt(args[1]);
      Path run = Paths.get(args[2]);
      String pluginArgument = args[3];
      Path pluginJar = pluginArgument.equals("-") ? null : Paths.get(pluginArgument);
      Path runtimeJar = Paths.get(args[4]);
      Path namedMapping = Paths.get(args[5]);
      String runtimeMapping = args[6];
      int expectedPlugins = Integer.parseInt(args[7]);
      int expectedModules = Integer.parseInt(args[8]);

      Class<?> platform = Class.forName("dev.slinky.inject.Platform");
      Class<?> namespace = Class.forName("dev.slinky.inject.Platform$Namespace");
      @SuppressWarnings({"unchecked", "rawtypes"})
      Object value = Enum.valueOf((Class)namespace, id == 101 ? "NAMED" : id == 102 ? "SRG" : "OFFICIAL");
      Method initialize = platform.getDeclaredMethod("initialize", namespace, String.class, boolean.class);
      initialize.setAccessible(true);
      Path payload = run.resolve("payload-" + id + ".jar");
      check(Files.isRegularFile(payload), "missing run payload " + payload);
      initialize.invoke(null, value, payload.toString(), false);
      if (runtimeMapping.equals("-")) {
         Mappings.load(null);
      } else {
         try (InputStream input = Files.newInputStream(Paths.get(runtimeMapping))) {
            Mappings.load(input);
         }
      }

      ModuleManager manager = new ModuleManager(new ClientContext(null));
      int loaded = PluginLoader.load(manager);
      check(loaded == expectedPlugins, "loaded plugins " + loaded + " expected " + expectedPlugins);
      check(manager.all().size() == expectedModules, "modules " + manager.all().size() + " expected " + expectedModules);

      Map<String, Boolean> enabled = new LinkedHashMap<>();
      Map<String, List<String>> formats = new LinkedHashMap<>();
      for (Module module : manager.all()) {
         check(!module.getId().isEmpty(), "empty module id");
         check(!module.getName().isEmpty(), "empty module name");
         check(manager.find(module.getId()) == module, "find by id for " + module.getId());
         check(manager.find(module.getName()) == module, "find by name for " + module.getName());
         int category = module.metadataCategory();
         check(category >= 0 && category <= 4, "metadata category " + category + " for " + module.getName());
         check(GuiAppearance.description(module) != null, "description for " + module.getName());
         List<Setting<?>> visible = module.visibleSettings();
         check(visible.size() == module.getSettings().size(), "visible settings for " + module.getName());
         manager.setEnabled(module, true, true);
         manager.applyPendingChanges();
         check(module.isEnabled(), "enable " + module.getName());
         enabled.put(module.getId(), true);
         List<String> values = new ArrayList<>();
         for (Setting<?> setting : module.getSettings()) {
            values.add(setting.getKey() + "=" + setting.format());
         }
         formats.put(module.getId(), values);
      }

      Path config = run.resolve("config-" + id + ".json");
      new ConfigStore(config).save(manager);

      ModuleManager restored = new ModuleManager(new ClientContext(null));
      int restoredPlugins = PluginLoader.load(restored);
      check(restoredPlugins == expectedPlugins, "restored plugins " + restoredPlugins);
      check(restored.all().size() == expectedModules, "restored modules " + restored.all().size());
      new ConfigStore(config).load(restored);
      restored.applyPendingChanges();
      for (Module module : restored.all()) {
         check(Boolean.TRUE.equals(enabled.get(module.getId())), "restored enabled " + module.getId());
         check(module.isEnabled(), "restored state " + module.getId());
         List<String> expected = formats.get(module.getId());
         check(expected != null, "restored module " + module.getId());
         if (expected != null) {
            List<String> actual = new ArrayList<>();
            for (Setting<?> setting : module.getSettings()) {
               actual.add(setting.getKey() + "=" + setting.format());
            }
            check(expected.equals(actual), "restored settings for " + module.getId());
         }
      }

      if (pluginJar != null) {
         verifyReferences(pluginJar, runtimeJar, classNames(namedMapping));
      }
      System.out.println("load " + id + ": " + loaded + " plugins, " + manager.all().size() + " modules");
   }

   // --------------------------------------------------------------- mapping

   private static void mapping(String[] args) throws Exception {
      Path map = Paths.get(args[1]);
      Path runtimeJar = Paths.get(args[2]);
      Path sourceJar = Paths.get(args[3]);
      List<String[]> lines = new ArrayList<>();
      for (String line : Files.readAllLines(map, StandardCharsets.UTF_8)) {
         if (!line.isEmpty()) {
            lines.add(line.split("\t"));
         }
      }
      try (InputStream input = Files.newInputStream(map)) {
         Mappings.load(input);
      }

      Set<String> runtimeClasses = new HashSet<>();
      Map<String, Set<String>> forward = new HashMap<>();
      Map<String, Set<String>> reverse = new HashMap<>();
      int classes = 0;
      for (String[] row : lines) {
         if (row[0].equals("CL")) {
            classes++;
            forward.computeIfAbsent(row[1], key -> new HashSet<>()).add(row[2]);
            reverse.computeIfAbsent(row[2], key -> new HashSet<>()).add(row[1]);
            runtimeClasses.add(row[2]);
         }
      }
      check(classes > 2000, "class mapping count " + classes);
      for (Map.Entry<String, Set<String>> entry : forward.entrySet()) {
         check(entry.getValue().size() == 1, "forward ambiguity " + entry.getKey());
      }
      for (Map.Entry<String, Set<String>> entry : reverse.entrySet()) {
         check(entry.getValue().size() == 1, "reverse ambiguity " + entry.getKey());
      }

      Map<String, ClassInfo> runtime = readJar(runtimeJar);
      Map<String, ClassInfo> source = readJar(sourceJar);
      int fields = 0;
      int methods = 0;
      int presentClasses = 0;
      int skippedClasses = 0;
      int skippedMembers = 0;
      for (String[] row : lines) {
         switch (row[0]) {
            case "CL":
               check(Mappings.mapClass(row[1]).equals(row[2]), "mapClass " + row[1]);
               check(Mappings.unmapClass(row[2]).equals(row[1]), "unmapClass " + row[2]);
               if (source.containsKey(row[1])) {
                  presentClasses++;
                  check(runtime.containsKey(row[2]), "runtime class missing " + row[1]);
               } else {
                  skippedClasses++;
               }
               break;
            case "FD":
               fields++;
               check(Mappings.mapField(row[1], row[2]).equals(row[3]), "mapField " + row[1] + "." + row[2]);
               if (!hasField(source, row[1], row[2])) {
                  skippedMembers++;
               } else {
                  check(hasField(runtime, Mappings.mapClass(row[1]), row[3]), "runtime field missing " + row[1] + "." + row[3]);
               }
               break;
            case "MD":
               methods++;
               check(Mappings.mapMethod(row[1], row[2], row[3]).equals(row[4]), "mapMethod " + row[1] + "." + row[2] + row[3]);
               if (!hasMethod(source, row[1], row[2], row[3])) {
                  skippedMembers++;
               } else {
                  check(hasMethod(runtime, Mappings.mapClass(row[1]), row[4], Mappings.mapDescriptor(row[3])),
                     "runtime method missing " + row[1] + "." + row[4] + row[3]);
               }
               break;
            default:
               break;
         }
      }
      check(fields > 5000, "field mapping count " + fields);
      check(methods > 5000, "method mapping count " + methods);

      Random random = new Random(0x5A17);
      List<String> classNames = new ArrayList<>(forward.keySet());
      for (int i = 0; i < 2000; i++) {
         String a = classNames.get(random.nextInt(classNames.size()));
         String b = classNames.get(random.nextInt(classNames.size()));
         String c = classNames.get(random.nextInt(classNames.size()));
         String descriptor = "(L" + a + ";I[L" + b + ";D)[L" + c + ";[Z";
         String mapped = Mappings.mapDescriptor(descriptor);
         check(Mappings.unmapDescriptor(mapped).equals(descriptor), "descriptor round trip " + descriptor);
      }
      check(presentClasses > 2400, "present classes " + presentClasses);
      System.out.println("mapping " + map.getFileName() + ": " + classes + " classes (" + presentClasses
         + " in jar, " + skippedClasses + " server-only), " + fields + " fields, " + methods
         + " methods (" + skippedMembers + " server-only)");
   }

   // ------------------------------------------------------------- utilities

   private static Set<String> classNames(Path mapping) throws IOException {
      Set<String> names = new HashSet<>();
      for (String line : Files.readAllLines(mapping, StandardCharsets.UTF_8)) {
         if (line.startsWith("CL\t")) {
            String[] row = line.split("\t");
            names.add(row[1]);
         }
      }
      return names;
   }

   private static final class ClassInfo {
      String superName;
      List<String> interfaces = new ArrayList<>();
      Map<String, Set<String>> fields = new HashMap<>();
      Map<String, Set<String>> methods = new HashMap<>();
   }

   private static Map<String, ClassInfo> readJar(Path jar) throws IOException {
      Map<String, ClassInfo> result = new HashMap<>();
      try (JarFile file = new JarFile(jar.toFile())) {
         for (JarEntry entry : Collections.list(file.entries())) {
            if (!entry.getName().endsWith(".class")) {
               continue;
            }
            try (InputStream input = file.getInputStream(entry)) {
               ClassReader reader = new ClassReader(input);
               ClassInfo info = new ClassInfo();
               result.put(reader.getClassName(), info);
               reader.accept(new ClassVisitor(Opcodes.ASM9) {
                  public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                     info.superName = superName;
                     if (interfaces != null) {
                        Collections.addAll(info.interfaces, interfaces);
                     }
                  }

                  public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                     info.fields.computeIfAbsent(name, key -> new HashSet<>()).add(descriptor);
                     return null;
                  }

                  public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                     info.methods.computeIfAbsent(name, key -> new HashSet<>()).add(descriptor);
                     return null;
                  }
               }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
         }
      }
      return result;
   }

   private static boolean hasField(Map<String, ClassInfo> runtime, String owner, String name) {
      for (String type : hierarchy(runtime, owner)) {
         ClassInfo info = runtime.get(type);
         if (info != null && info.fields.containsKey(name)) {
            return true;
         }
      }
      return false;
   }

   private static boolean hasMethod(Map<String, ClassInfo> runtime, String owner, String name, String descriptor) {
      for (String type : hierarchy(runtime, owner)) {
         ClassInfo info = runtime.get(type);
         if (info != null && info.methods.containsKey(name) && info.methods.get(name).contains(descriptor)) {
            return true;
         }
      }
      return false;
   }

   private static Set<String> hierarchy(Map<String, ClassInfo> runtime, String owner) {
      Set<String> visited = new LinkedHashSet<>();
      Deque<String> queue = new ArrayDeque<>();
      queue.add(owner);
      while (!queue.isEmpty()) {
         String current = queue.poll();
         if (current == null || !visited.add(current)) {
            continue;
         }
         ClassInfo info = runtime.get(current);
         if (info == null) {
            continue;
         }
         if (info.superName != null) {
            queue.add(info.superName);
         }
         queue.addAll(info.interfaces);
      }
      return visited;
   }

   private static void verifyReferences(Path pluginJar, Path runtimeJar, Set<String> namedClasses) throws IOException {
      Map<String, ClassInfo> runtime = readJar(runtimeJar);
      Set<String> runtimeClasses = runtime.keySet();
      int checked = 0;
      int classes = 0;
      try (JarFile file = new JarFile(pluginJar.toFile())) {
         for (JarEntry entry : Collections.list(file.entries())) {
            if (!entry.getName().endsWith(".class")) {
               continue;
            }
            classes++;
            try (InputStream input = file.getInputStream(entry)) {
               ClassReader reader = new ClassReader(input);
               final int[] counter = {0};
               reader.accept(new ClassVisitor(Opcodes.ASM9) {
                  private void type(String internal) {
                     if (internal == null) {
                        return;
                     }
                     counter[0]++;
                     if (runtimeClasses.contains(internal)) {
                        return;
                     }
                     check(!namedClasses.contains(internal), "unmapped named type " + internal + " in " + reader.getClassName());
                  }

                  private void descriptor(String descriptor) {
                     if (descriptor == null) {
                        return;
                     }
                     int index = 0;
                     while (index < descriptor.length()) {
                        char c = descriptor.charAt(index);
                        if (c == 'L') {
                           int end = descriptor.indexOf(';', index);
                           if (end < 0) {
                              return;
                           }
                           type(descriptor.substring(index + 1, end));
                           index = end + 1;
                        } else {
                           index++;
                        }
                     }
                  }

                  public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                     type(superName);
                     if (interfaces != null) {
                        for (String itf : interfaces) {
                           type(itf);
                        }
                     }
                  }

                  public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                     descriptor(descriptor);
                     return null;
                  }

                  public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                     descriptor(descriptor);
                     if (exceptions != null) {
                        for (String exception : exceptions) {
                           type(exception);
                        }
                     }
                     return new MethodVisitor(Opcodes.ASM9) {
                        public void visitTypeInsn(int opcode, String typeName) {
                           type(typeName);
                        }

                        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                           type(owner);
                           descriptor(descriptor);
                           if (runtimeClasses.contains(owner) && !hasField(runtime, owner, name)) {
                              failures++;
                              System.out.println("FAIL missing field " + owner + "." + name + " in " + reader.getClassName());
                           }
                        }

                        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean itf) {
                           type(owner);
                           descriptor(descriptor);
                           if (runtimeClasses.contains(owner) && !name.startsWith("<")
                              && !hasMethod(runtime, owner, name, descriptor)) {
                              failures++;
                              System.out.println("FAIL missing method " + owner + "." + name + descriptor + " in " + reader.getClassName());
                           }
                        }

                        public void visitMultiANewArrayInsn(String descriptor, int dimensions) {
                           descriptor(descriptor);
                        }

                        public void visitLdcInsn(Object value) {
                           if (value instanceof Type) {
                              Type type = (Type)value;
                              if (type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY) {
                                 descriptor(type.getDescriptor());
                              }
                           } else if (value instanceof Handle) {
                              Handle handle = (Handle)value;
                              type(handle.getOwner());
                              descriptor(handle.getDesc());
                           } else if (value instanceof ConstantDynamic) {
                              ConstantDynamic dynamic = (ConstantDynamic)value;
                              descriptor(dynamic.getDescriptor());
                           }
                        }

                        public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrap, Object... arguments) {
                           descriptor(descriptor);
                           if (bootstrap != null) {
                              type(bootstrap.getOwner());
                              descriptor(bootstrap.getDesc());
                           }
                           for (Object argument : arguments) {
                              if (argument instanceof Type) {
                                 Type type = (Type)argument;
                                 if (type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY) {
                                    descriptor(type.getDescriptor());
                                 }
                              } else if (argument instanceof Handle) {
                                 Handle handle = (Handle)argument;
                                 type(handle.getOwner());
                                 descriptor(handle.getDesc());
                              }
                           }
                        }

                        public void visitTryCatchBlock(Label start, Label end, Label handler, String typeName) {
                           type(typeName);
                        }
                     };
                  }
               }, 0);
               checked += counter[0];
            }
         }
      }
      check(classes > 0, "no plugin classes in " + pluginJar);
      System.out.println("references: " + checked + " types across " + classes + " classes verified against " + runtimeJar.getFileName());
   }
}
