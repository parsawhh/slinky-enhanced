package inject

import "fmt"

// Process represents a discovered Minecraft JVM process.
type Process struct {
	PID  uint32
	Name string
}

// FindMinecraft searches running processes for a Minecraft JVM instance.
// Returns nil, nil when no candidate is found (caller should retry / prompt).
func FindMinecraft() (*Process, error) {
	return findMinecraft()
}

// Inject loads the Slinky payload JAR into the target JVM.
// On Windows this calls LoadLibraryW on SlinkyNative.dll which in turn
// uses the JVM Attach API or JVMTI to bootstrap the agent.
// On Linux it uses the JVM Attach API directly via a UNIX socket.
func (p *Process) Inject(jarPath string) error {
	if err := p.loadLib(jarPath); err != nil {
		return fmt.Errorf("loadLib: %w", err)
	}
	return nil
}

// loadLib is the platform-specific injection entry point (see inject_windows.go).
func (p *Process) loadLib(jarPath string) error {
	return platformInject(p, jarPath)
}

// findLibBase locates the base address of a loaded library inside the target
// process (used on Windows to resolve the bootstrap export).
func (p *Process) findLibBase(name string) (uintptr, error) {
	return platformFindLibBase(p, name)
}

// findInitFunc resolves the address of the init/bootstrap export in the
// already-loaded SlinkyNative.dll inside the target process.
func (p *Process) findInitFunc(base uintptr) (uintptr, error) {
	return platformFindInitFunc(p, base)
}
