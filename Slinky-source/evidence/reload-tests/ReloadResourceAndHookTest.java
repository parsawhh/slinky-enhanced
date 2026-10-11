import dev.slinky.inject.Platform;
import dev.slinky.inject.ReloadState;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ReloadResourceAndHookTest {
    static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        Thread first = new Thread(() -> {}, "test-reload-save-first");
        Thread second = new Thread(() -> {}, "test-reload-save-second");
        ReloadState.replaceHook(first);
        ReloadState.replaceHook(second);
        require(!Runtime.getRuntime().removeShutdownHook(first), "replacement must remove old hook");
        require(Runtime.getRuntime().removeShutdownHook(second), "replacement must register new hook");
        Field hook = ReloadState.class.getDeclaredField("shutdownHook"); hook.setAccessible(true);
        require(hook.get(null) == second, "bookkeeping must point to the replacement");
        ReloadState.replaceHook(first);
        boolean failed = false;
        try { ReloadState.replaceHook(null); } catch (NullPointerException expected) { failed = true; }
        require(failed && hook.get(null) == first, "failed replacement must preserve bookkeeping");
        require(Runtime.getRuntime().removeShutdownHook(first), "failed replacement must restore previous hook");
        hook.set(null, null);
        for (Object value : System.getProperties().values()) require(value instanceof String, "all system property values must remain strings");

        Path payload = Files.createTempFile(java.nio.file.Paths.get("work"), "reload-resource-", ".jar");
        try {
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(payload))) {
                zip.putNextEntry(new ZipEntry("reload-resource.txt"));
                zip.write("fresh payload resource".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            Method initialize = Platform.class.getDeclaredMethod("initialize", Platform.Namespace.class, String.class, boolean.class);
            initialize.setAccessible(true);
            initialize.invoke(null, Platform.Namespace.NAMED, payload.toString(), false);
            byte[] data = new byte[128];
            try (InputStream resource = Platform.openResource("/reload-resource.txt")) {
                require(new String(data, 0, resource.read(data), StandardCharsets.UTF_8).equals("fresh payload resource"), "fresh payload must override stale loader resource");
            }
            try (InputStream resource = Platform.openResource("/fallback-resource.txt")) {
                require(new String(data, 0, resource.read(data), StandardCharsets.UTF_8).equals("loader fallback"), "missing payload entry must fall back to loader resource");
            }
            Files.delete(payload);
            require(!Files.exists(payload), "closing resource must release the jar handle");
            initialize.invoke(null, Platform.Namespace.NAMED, null, false);
        } finally {
            Files.deleteIfExists(payload);
        }
        System.out.println("PASS: shutdown-hook replacement and rollback preserve string-only properties; fresh resource overrides loader resource and closes jar handle.");
    }
}
