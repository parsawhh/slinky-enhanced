import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;

/**
 * Optional developer helper that compiles plugin sources and produces one
 * remapped jar per runtime naming scheme.
 *
 * <p>This is not part of the client runtime. Build it and run it wherever a JDK
 * is available. It compiles against the named Minecraft jar and the named
 * payload, then reuses {@link RemapJar} to emit the SRG and official variants.
 * The in-game {@code PluginLoader} only opens the prebuilt jars.</p>
 */
public final class PluginBuilder {
    private static final String SERVICE = "dev.slinky.api.SlinkyPlugin";

    private PluginBuilder() {
    }

    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("usage: PluginBuilder <payload directory> <output directory>");
            System.exit(2);
        }
        Path output = Paths.get(args[1]);
        build(Paths.get(args[0]), output);
        if (!Files.isRegularFile(output.resolve("slinky-plugins-101.jar"))) {
            System.err.println("PluginBuilder: no plugin jar was produced.");
            System.exit(1);
        }
    }

    public static void build(Path root, Path output) {
        Path plugins = Paths.get(System.getProperty("user.home"), ".slinky", "plugins");
        if (!Files.isDirectory(plugins)) return;
        Path named = root.resolve("minecraft-1.8.9-named.jar");
        Path payload = root.resolve("payload-101.jar");
        if (!Files.isRegularFile(named) || !Files.isRegularFile(payload)) {
            System.err.println("Slinky plugins: missing minecraft-1.8.9-named.jar or payload-101.jar; skipping plugin build.");
            return;
        }
        List<Path> sources = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(plugins)) {
            walk.filter(path -> path.toString().endsWith(".java"))
                .filter(path -> !plugins.relativize(path).toString().contains(".build"))
                .sorted()
                .forEach(sources::add);
        } catch (IOException error) {
            System.err.println("Slinky plugins: cannot scan " + plugins + ": " + error.getMessage());
            return;
        }
        if (sources.isEmpty()) return;

        Path work = plugins.resolve(".build");
        Path classes = work.resolve("named-classes");
        try {
            deleteTree(classes);
            Files.createDirectories(classes);
            compile(sources, named, payload, classes);
            List<String> providers = findProviders(classes);
            Collections.sort(providers);
            if (providers.isEmpty()) {
                System.err.println("Slinky plugins: no class in " + plugins + " implements " + SERVICE + "; skipping.");
                return;
            }
            Path services = classes.resolve("META-INF/services");
            Files.createDirectories(services);
            Files.write(services.resolve(SERVICE), providers, StandardCharsets.UTF_8);
            Path namedJar = work.resolve("plugins-named.jar");
            writeJar(classes, namedJar);
            System.out.println("Slinky plugins: compiled " + sources.size() + " source file(s), " + providers.size() + " plugin(s).");
            emitVariants(root, named, namedJar, work, output);
            for (int id = 101; id <= 103; id++) {
                Path built = output.resolve("slinky-plugins-" + id + ".jar");
                if (Files.isRegularFile(built)) {
                    Files.copy(built, work.resolve(built.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (Exception error) {
            System.err.println("Slinky plugins: build failed: " + describe(error));
        }
    }

    private static void compile(List<Path> sources, Path named, Path payload, Path classes) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IOException("no system Java compiler; run Slinky with a full JDK");
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = files.getJavaFileObjectsFromPaths(sources);
            List<String> options = new ArrayList<>(Arrays.asList(
                "-encoding", "UTF-8",
                "-proc:none",
                "-classpath", named.toString() + File.pathSeparator + payload.toString(),
                "-d", classes.toString()));
            if (Runtime.version().feature() >= 9) {
                options.add("--release");
                options.add("8");
            }
            Boolean ok = compiler.getTask(new PrintWriter(System.err), files, diagnostics, options, null, units).call();
            if (ok == null || !ok) {
                int errors = 0;
                for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                    if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                        errors++;
                        System.err.println("Slinky plugin: " + diagnostic);
                    }
                }
                throw new IOException(errors + " compiler error(s)");
            }
        }
    }

    private static List<String> findProviders(Path classes) throws IOException {
        List<String> providers = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(classes)) {
            walk.filter(path -> path.toString().endsWith(".class")).forEach(path -> {
                try (InputStream input = Files.newInputStream(path)) {
                    ClassReader reader = new ClassReader(input);
                    if ((reader.getAccess() & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT)) != 0) return;
                    for (String name : reader.getInterfaces()) {
                        if (name.equals(SERVICE.replace('.', '/'))) {
                            providers.add(reader.getClassName().replace('/', '.'));
                            return;
                        }
                    }
                } catch (IOException ignored) {
                }
            });
        }
        return providers;
    }

    private static void emitVariants(Path root, Path namedJar, Path pluginJar, Path work, Path outputDirectory) throws Exception {
        Files.createDirectories(outputDirectory);
        Path copy = outputDirectory.resolve("slinky-plugins-101.jar");
        Files.copy(pluginJar, copy, StandardCopyOption.REPLACE_EXISTING);
        for (int id = 102; id <= 103; id++) {
            Path payload = root.resolve("payload-" + id + ".jar");
            if (!Files.isRegularFile(payload)) {
                System.err.println("Slinky plugins: missing " + payload.getFileName() + "; skipping that variant.");
                continue;
            }
            Path mapping = work.resolve("mappings-" + id + ".txt");
            extract(payload, "dev/slinky/inject/mappings.txt", mapping);
            if (!Files.isRegularFile(mapping)) {
                System.err.println("Slinky plugins: " + payload.getFileName() + " has no mapping table; skipping.");
                continue;
            }
            Path output = outputDirectory.resolve("slinky-plugins-" + id + ".jar");
            RemapJar.main(new String[]{mapping.toString(), "forward", pluginJar.toString(), output.toString(), namedJar.toString()});
        }
    }

    private static void extract(Path jar, String name, Path destination) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(name);
            if (entry == null) return;
            Files.createDirectories(destination.getParent());
            try (InputStream input = zip.getInputStream(entry)) {
                Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void writeJar(Path directory, Path jar) throws IOException {
        Files.createDirectories(jar.getParent());
        List<Path> entries = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(directory)) {
            walk.filter(Files::isRegularFile).sorted().forEach(entries::add);
        }
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (Path file : entries) {
                String name = directory.relativize(file).toString().replace(File.separatorChar, '/');
                ZipEntry entry = new ZipEntry(name);
                entry.setTime(315532800000L);
                output.putNextEntry(entry);
                try (InputStream input = Files.newInputStream(file)) {
                    copy(input, output);
                }
                output.closeEntry();
            }
        }
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
    }

    private static void deleteTree(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (Stream<Path> walk = Files.walk(directory)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException ignored) {
                }
            });
        }
    }

    private static String describe(Throwable error) {
        StringWriter writer = new StringWriter();
        error.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
