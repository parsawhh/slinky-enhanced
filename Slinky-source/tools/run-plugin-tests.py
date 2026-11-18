#!/usr/bin/env python3
"""Offline end-to-end and property tests for the Slinky drop-in plugin pipeline.

Builds real plugins from source, remaps them into all three naming schemes,
loads them through the production PluginLoader, round-trips their settings
through ConfigStore, and validates every remapped reference against the
matching Minecraft runtime jar. It also property-tests the mapping tables.
"""
import hashlib
import os
import random
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / 'build/linux'
DIST = ROOT / 'dist/linux-aarch64'
LAUNCHER = DIST / 'slinky-launcher.jar'
NAMED = BUILD / 'minecraft-1.8.9-named.jar'
OFFICIAL = ROOT / 'deps/minecraft-1.8.9-official.jar'
LIBS = sorted((ROOT / 'deps/libraries').glob('*.jar'))
SRG_MAP = ROOT / 'mappings/named-to-srg.txt'
OFFICIAL_MAP = ROOT / 'mappings/named-to-official.txt'
TMP_ROOT = Path('/data/data/com.termux/files/usr/tmp/opencode')
SEED = 0xC0FFEE


def run(command, **kwargs):
    print('+', ' '.join(str(part) for part in command))
    subprocess.run([str(part) for part in command], check=True, **kwargs)


def java_path():
    java = shutil.which('java')
    if not java:
        raise SystemExit('java is not on PATH')
    return java


def javac_path():
    javac = shutil.which('javac')
    if not javac:
        java = Path(java_path()).resolve()
        candidate = java.parent / 'javac'
        if not candidate.is_file():
            raise SystemExit('javac is not on PATH')
        return candidate
    return javac


def ensure_build():
    required = [LAUNCHER, NAMED, OFFICIAL, SRG_MAP, OFFICIAL_MAP]
    required += [BUILD / 'payload-{}.jar'.format(i) for i in (101, 102, 103)]
    if all(path.is_file() for path in required):
        return
    print('Build artifacts missing; running --java-only build first.')
    run([sys.executable, ROOT / 'tools/build-linux.py', '--java-only'], cwd=ROOT)


def classpath(*entries):
    flat = []
    for entry in entries:
        if isinstance(entry, (list, tuple)):
            flat.extend(str(item) for item in entry)
        else:
            flat.append(str(entry))
    return ':'.join(flat)


def write_rich_plugin(directory):
    directory.mkdir(parents=True, exist_ok=True)
    source = '''package plugins.e2e;

import dev.slinky.api.PluginContext;
import dev.slinky.api.PluginModule;
import dev.slinky.api.SlinkyPlugin;
import dev.slinky.client.ClientContext;
import dev.slinky.client.SemanticEvent;
import dev.slinky.inject.NativeBridge;
import dev.slinky.module.ModuleCategory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class E2EPlugin implements SlinkyPlugin {
   @Override
   public void onLoad(PluginContext context) {
      context.log("e2e plugin loading");
      context.register(new Combat());
      context.register(new Visual());
      context.register(new Utility());
   }

   public static final class Combat extends PluginModule {
      private final List<String> seen = new ArrayList<>();

      public Combat() {
         super("e2e_combat", "E2E Combat", "Exercises player access", ModuleCategory.COMBAT);
         this.bool("enabled_check", "Enabled Check", true);
         this.number("range", "Range", 3.5, 0.0, 6.0, 0.1);
         this.choice("mode", "Mode", 1, "Single", "Multi", "Switch");
         this.list("friends", "Friends", Arrays.asList("Alice", "Боб"));
         this.colorSetting("tint", "Tint");
      }

      @Override
      public boolean listens(int type) {
         return type == 17;
      }

      @Override
      public void onEvent(ClientContext context, SemanticEvent event) {
         if (event.type != 17 || !context.ready()) {
            return;
         }
         if (this.flag("enabled_check") && context.player().onGround) {
            context.player().jump();
         }
         if (event.entity != null) {
            this.seen.add(event.entity.getName());
         }
         if (event.packet != null) {
            this.seen.add(event.packet.getClass().getName());
         }
         this.seen.removeIf(value -> value.isEmpty());
         context.world();
      }
   }

   public static final class Visual extends PluginModule {
      public Visual() {
         super("e2e_visual", "E2E Visual", "Exercises render access", ModuleCategory.RENDER);
         this.bool("glow", "Glow", false);
         this.number("scale", "Scale", 1.0, 0.25, 4.0, 0.25);
      }

      @Override
      public void onRenderTick(ClientContext context) {
         if (context.ready()) {
            context.player().setSprinting(context.player().isSprinting());
         }
      }
   }

   public static final class Utility extends PluginModule {
      public Utility() {
         super("e2e utility", "E2E Utility", "Exercises native access", ModuleCategory.UTILITY);
         this.bool("native", "Native", true);
         this.choice("channel", "Channel", 0, "Main", "Alt");
      }

      @Override
      public void onEnable(ClientContext context) {
         Class<?> bridge = NativeBridge.class;
         if (bridge == null) {
            throw new IllegalStateException();
         }
      }
   }
}
'''
    (directory / 'E2EPlugin.java').write_text(source, encoding='utf-8')


def write_edge_plugin(directory):
    directory.mkdir(parents=True, exist_ok=True)
    source = '''package plugins.edge;

import dev.slinky.api.PluginContext;
import dev.slinky.api.PluginModule;
import dev.slinky.api.SlinkyPlugin;
import dev.slinky.module.ModuleCategory;
import java.util.Arrays;

public final class EdgePlugin implements SlinkyPlugin {
   @Override
   public void onLoad(PluginContext context) {
      context.register(new Odd());
   }

   public static final class Odd extends PluginModule {
      public Odd() {
         super("Weird Id-With_Spaces", "Weird Name", "Unusual identifiers", ModuleCategory.UNKNOWN);
         this.bool("flag.with.dots", "Dotted Key", false);
         this.list("names", "Names", Arrays.asList("", "naïve", "日本語"));
         this.number("tiny", "Tiny", 0.0, -1.0, 1.0, 0.5);
      }
   }
}
'''
    (directory / 'EdgePlugin.java').write_text(source, encoding='utf-8')


SETTING_TYPES = ('bool', 'number', 'choice', 'list', 'color')
CATEGORIES = ('COMBAT', 'MOVEMENT', 'RENDER', 'WORLD', 'UTILITY', 'BLOCKS', 'UNKNOWN')
SNIPPETS = (
    'if (context.player() != null) { boolean ground = context.player().onGround; }',
    'if (context.player() != null) { context.player().jump(); }',
    'if (context.player() != null) { context.player().setSprinting(context.player().isSprinting()); }',
    'if (event.entity != null) { String name = event.entity.getName(); }',
    'if (event.packet != null) { Object packet = event.packet; }',
    'context.world();',
    'Class<?> bridge = dev.slinky.inject.NativeBridge.class;',
    'java.util.List<String> values = new java.util.ArrayList<>(); values.removeIf(value -> value.isEmpty());',
)


def write_property_plugin(directory, index, rng):
    modules = rng.randint(1, 2)
    body = []
    expected = []
    for module in range(modules):
        module_id = 'prop_{}_{}'.format(index, module)
        category = rng.choice(CATEGORIES)
        settings = []
        for setting in range(rng.randint(1, 4)):
            kind = rng.choice(SETTING_TYPES)
            key = 'set_{}_{}'.format(module, setting)
            label = 'Setting {} {}'.format(module, setting)
            if kind == 'bool':
                settings.append('this.bool("{}", "{}", {});'.format(key, label, 'true' if rng.random() < 0.5 else 'false'))
            elif kind == 'number':
                low = rng.randint(-10, 0)
                high = rng.randint(1, 20)
                value = round(rng.uniform(low, high), 2)
                settings.append('this.number("{}", "{}", {}, {}.0, {}.0, 0.5);'.format(key, label, value, low, high))
            elif kind == 'choice':
                count = rng.randint(2, 4)
                options = ', '.join('"Option {}"'.format(i) for i in range(count))
                settings.append('this.choice("{}", "{}", {}, {});'.format(key, label, rng.randint(0, count - 1), options))
            elif kind == 'list':
                values = ', '.join('"v{}_{}"'.format(module, i) for i in range(rng.randint(0, 3)))
                settings.append('this.list("{}", "{}", java.util.Arrays.asList({}));'.format(key, label, values))
            else:
                settings.append('this.colorSetting("{}", "{}");'.format(key, label))
        snippets = rng.sample(SNIPPETS, rng.randint(1, 3))
        event_code = '\n         '.join(snippets)
        body.append('''   public static final class M{module} extends PluginModule {{
      public M{module}() {{
         super("{module_id}", "Prop {index} M{module}", "Property module", ModuleCategory.{category});
         {settings}
      }}

      @Override
      public boolean listens(int type) {{
         return type == 17;
      }}

      @Override
      public void onEvent(dev.slinky.client.ClientContext context, dev.slinky.client.SemanticEvent event) {{
         {event_code}
      }}
   }}
'''.format(module=module, module_id=module_id, index=index, category=category,
           settings=' '.join(settings), event_code=event_code))
        expected.append(module_id)
    registrations = '\n'.join('      context.register(new M{}());'.format(m) for m in range(modules))
    source = '''package plugins.prop{index};

import dev.slinky.api.PluginContext;
import dev.slinky.api.PluginModule;
import dev.slinky.api.SlinkyPlugin;
import dev.slinky.module.ModuleCategory;

public final class Plugin{index} implements SlinkyPlugin {{
   @Override
   public void onLoad(PluginContext context) {{
{registrations}
   }}

{body}}}
'''.format(index=index, registrations=registrations, body='\n'.join(body))
    (directory / 'Plugin{}.java'.format(index)).write_text(source, encoding='utf-8')
    return modules


def sha256(path):
    digest = hashlib.sha256()
    with open(path, 'rb') as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b''):
            digest.update(chunk)
    return digest.hexdigest()


def with_manifest_attribute(source, destination, attribute, value):
    import zipfile

    with zipfile.ZipFile(source) as source_zip, zipfile.ZipFile(destination, 'w', zipfile.ZIP_DEFLATED) as dest_zip:
        try:
            manifest = source_zip.read('META-INF/MANIFEST.MF').decode('utf-8')
        except KeyError:
            manifest = 'Manifest-Version: 1.0\n'
        if not manifest.endswith('\n'):
            manifest += '\n'
        manifest += '{}: {}\n'.format(attribute, value)
        wrote_manifest = False
        for info in source_zip.infolist():
            data = source_zip.read(info.filename)
            if info.filename == 'META-INF/MANIFEST.MF':
                data = manifest.encode('utf-8')
                wrote_manifest = True
            entry = zipfile.ZipInfo(info.filename, date_time=(1980, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            dest_zip.writestr(entry, data)
        if not wrote_manifest:
            entry = zipfile.ZipInfo('META-INF/MANIFEST.MF', date_time=(1980, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            dest_zip.writestr(entry, manifest.encode('utf-8'))


def strip_signatures(source, destination):
    import zipfile

    with zipfile.ZipFile(source) as source_zip, zipfile.ZipFile(destination, 'w', zipfile.ZIP_DEFLATED) as dest_zip:
        for info in source_zip.infolist():
            upper = info.filename.upper()
            if upper.startswith('META-INF/') and upper.endswith(('.SF', '.RSA', '.DSA', '.EC')):
                continue
            data = source_zip.read(info.filename)
            entry = zipfile.ZipInfo(info.filename, date_time=(1980, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            dest_zip.writestr(entry, data)


def main():
    ensure_build()
    java = java_path()
    TMP_ROOT.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix='slinky-plugin-tests-', dir=TMP_ROOT))
    home = work / 'home'
    plugins = home / '.slinky/plugins'
    plugins.mkdir(parents=True)
    tooling = work / 'tooling'
    tooling.mkdir()
    run([javac_path(), '-cp', classpath(ROOT / 'deps/tooling/*'), '-d', tooling,
         ROOT / 'tools/PluginBuilder.java', ROOT / 'tools/RemapJar.java'])
    tooling_cp = classpath(tooling, ROOT / 'deps/tooling/*')
    harness = work / 'harness'
    harness.mkdir()
    run_a = work / 'run-a'
    run_b = work / 'run-b'
    run_a.mkdir()
    run_b.mkdir()

    rng = random.Random(SEED)
    write_rich_plugin(plugins)
    write_edge_plugin(plugins)
    property_plugins = 8
    expected_modules = 4  # rich(3) + edge(1)
    for index in range(property_plugins):
        expected_modules += write_property_plugin(plugins, index, rng)
    expected_plugins = property_plugins + 2

    print('Work directory:', work)
    print('Expected:', expected_plugins, 'plugins,', expected_modules, 'modules')
    run([javac_path(), '-cp', classpath(BUILD / 'payload-101.jar', NAMED, LAUNCHER, tooling, ROOT / 'deps/tooling/*'), '-d', harness,
         ROOT / 'tests/PluginTestMain.java'])

    builder_cp = classpath(tooling_cp, harness)
    run([java, '-Duser.home={}'.format(home), '-cp', builder_cp, 'PluginTestMain', 'builder', BUILD, run_a])
    run([java, '-Duser.home={}'.format(home), '-cp', builder_cp, 'PluginTestMain', 'builder', BUILD, run_b])

    for variant in (101, 102, 103):
        name = 'slinky-plugins-{}.jar'.format(variant)
        first = run_a / name
        second = run_b / name
        if not first.is_file():
            raise SystemExit('missing built artifact ' + name)
        if sha256(first) != sha256(second):
            raise SystemExit('non-deterministic build for ' + name)
        print('deterministic:', name, sha256(first)[:16])

    official_jar = work / 'minecraft-1.8.9-official-unsigned.jar'
    strip_signatures(OFFICIAL, official_jar)

    srg_jar = work / 'minecraft-1.8.9-srg.jar'
    run([java, '-cp', tooling_cp, 'RemapJar', SRG_MAP, 'forward', NAMED, srg_jar, NAMED])
    if not srg_jar.is_file():
        raise SystemExit('could not produce the SRG runtime jar')

    runtime = {101: NAMED, 102: srg_jar, 103: official_jar}
    mapping = {101: '-', 102: SRG_MAP, 103: OFFICIAL_MAP}
    for variant in (101, 102, 103):
        shutil.copyfile(BUILD / 'payload-{}.jar'.format(variant), run_a / 'payload-{}.jar'.format(variant))
        harness_variant = work / 'harness-{}'.format(variant)
        harness_variant.mkdir(exist_ok=True)
        run([javac_path(), '-cp', classpath(BUILD / 'payload-{}.jar'.format(variant), runtime[variant], LIBS, LAUNCHER, tooling, ROOT / 'deps/tooling/*'),
             '-d', harness_variant, ROOT / 'tests/PluginTestMain.java'])
        base = classpath(harness_variant, BUILD / 'payload-{}.jar'.format(variant), runtime[variant], LIBS, LAUNCHER, tooling, ROOT / 'deps/tooling/*')
        if variant == 101:
            base_101 = base
        elif variant == 103:
            base_103 = base
        print('\n=== end-to-end load: variant', variant, '===')
        run([java, '-Duser.home={}'.format(home), '-cp', base, 'PluginTestMain', 'load',
             str(variant), str(run_a), str(run_a / 'slinky-plugins-{}.jar'.format(variant)),
             str(runtime[variant]), str(OFFICIAL_MAP), str(mapping[variant]),
             str(expected_plugins), str(expected_modules)])

        empty = work / 'empty-{}'.format(variant)
        empty.mkdir(exist_ok=True)
        shutil.copyfile(BUILD / 'payload-{}.jar'.format(variant), empty / 'payload-{}.jar'.format(variant))
        empty_home = work / 'empty-home-{}'.format(variant)
        empty_home.mkdir(exist_ok=True)
        print('\n=== no-plugin negative: variant', variant, '===')
        run([java, '-Duser.home={}'.format(empty_home), '-cp', base, 'PluginTestMain', 'load',
             str(variant), str(empty), '-', str(runtime[variant]), str(OFFICIAL_MAP), str(mapping[variant]),
             '0', '0'])

    for variant, mapping_path, jar in ((102, SRG_MAP, srg_jar), (103, OFFICIAL_MAP, OFFICIAL)):
        print('\n=== mapping properties:', mapping_path.name, '===')
        run([java, '-cp', classpath(harness, BUILD / 'payload-101.jar', LAUNCHER, jar, tooling, ROOT / 'deps/tooling/*'),
             'PluginTestMain', 'mapping', str(mapping_path), str(jar), str(NAMED)])

    framework_tests = [
        ('manifest official', 103, 'slinky-plugins-103.jar', 'official', 10),
        ('manifest mismatch skipped', 101, 'slinky-plugins-103.jar', 'official', 0),
    ]
    for label, variant, artifact, declared, expected in framework_tests:
        directory = work / ('manifest-{}'.format(variant))
        directory.mkdir(exist_ok=True)
        shutil.copyfile(BUILD / 'payload-{}.jar'.format(variant), directory / 'payload-{}.jar'.format(variant))
        with_manifest_attribute(run_a / artifact, directory / 'custom-plugin.jar', 'Slinky-Namespace', declared)
        print('\n=== {}: variant {} ==='.format(label, variant))
        run([java, '-Duser.home={}'.format(work / 'manifest-home'), '-cp', base_103 if variant == 103 else base_101,
             'PluginTestMain', 'load', str(variant), str(directory), str(directory / 'custom-plugin.jar'),
             str(runtime[variant]), str(OFFICIAL_MAP), str(mapping[variant]), str(expected),
             str(expected_modules if expected else 0)])

    plain = work / 'plain-103'
    plain.mkdir(exist_ok=True)
    shutil.copyfile(BUILD / 'payload-103.jar', plain / 'payload-103.jar')
    shutil.copyfile(run_a / 'slinky-plugins-103.jar', plain / 'any-name.jar')
    print('\n=== unmarked jar loads for variant 103 ===')
    run([java, '-Duser.home={}'.format(work / 'plain-home'), '-cp', base_103, 'PluginTestMain', 'load',
         '103', str(plain), str(plain / 'any-name.jar'), str(runtime[103]), str(OFFICIAL_MAP),
         str(mapping[103]), str(expected_plugins), str(expected_modules)])

    print('\nAll plugin tests passed. Work directory retained at', work)


if __name__ == '__main__':
    try:
        main()
    except subprocess.CalledProcessError as error:
        raise SystemExit('FAILED: command exited with {}'.format(error.returncode))
