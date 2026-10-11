SLINKY — LINUX x86-64

Extract this folder, open a terminal in it, and run:

    ./slinky

Start Lunar/Minecraft 1.8.9 first. Run the launcher as the same Linux user as
the game. Press Right Shift in the game to open the menu. Use its Unload action,
then launch again to reload. Startup progress and errors appear in the terminal.

If your game is not selected automatically:

    ./slinky --list
    ./slinky --pid 12345

Requirements

- Linux x86-64 with glibc 2.34+ and libstdc++ exposing GLIBCXX_3.4.29+.
  Ubuntu 22.04+, Mint 21+, Debian 12+, and equivalent newer desktops meet the
  native runtime versions. Other architectures/older systems can rebuild source.
- A full JDK 11 or newer for this launcher, with the jdk.attach module. JAVA_HOME
  can select it. Minecraft may keep using its own Java 8 or newer runtime.
- The target JVM must permit local attach/native agent loading. The launcher
  explains disabled-attach/agent-loading errors and prints the startup log path.

Keep slinky, slinky-launcher.jar, libSlinkyNative.so and all three payload JARs
together. The Java payload is the same implementation as the Windows build;
only the launcher/native platform glue differs. No root access is needed.

Testing

Built on Ubuntu 24.04 x86-64 with OpenJDK 21. Eight isolated real Linux JVM cases
passed: all three naming variants, class capture/rewrite/retransform, active
skip, unload/update/reload, incompatible-update rejection and recovery, custom
class loaders, concurrent starts, and Unicode paths. Java class bodies and
assets match the Windows variants. Actual Linux Lunar rendering/menu behavior
has not been tested here; the Linux game installation is unavailable.

Implementation uses the JDK Attach API and the existing shared JVMTI bridge:
https://docs.oracle.com/en/java/javase/21/docs/api/jdk.attach/com/sun/tools/attach/VirtualMachine.html

Source build (from the source bundle):

    ./build-linux.sh

Needs a full JDK 11+, Python 3, CMake 3.24+, and a C++17 compiler. The included
pinned dependencies allow an offline build. If missing, explicitly restore with:

    ./build-linux.sh --restore-dependencies
