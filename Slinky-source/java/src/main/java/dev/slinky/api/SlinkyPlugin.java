package dev.slinky.api;

/**
 * Entry point for a drop-in plugin.
 *
 * <p>A plugin is either a single {@code .java} file dropped into
 * {@code ~/.slinky/plugins} or a compiled {@code .jar} that declares an
 * implementation through {@code META-INF/services/dev.slinky.api.SlinkyPlugin}.
 * The loader creates one no-argument instance and calls {@link #onLoad}.</p>
 */
public interface SlinkyPlugin {
   void onLoad(PluginContext context);
}
