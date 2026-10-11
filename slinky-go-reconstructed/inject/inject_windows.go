package inject

import (
	"fmt"
	"io"
	"os"
	"path/filepath"
	"syscall"
	"unsafe"
)

var (
	kernel32          = syscall.NewLazyDLL("kernel32.dll")
	procOpenProcess   = kernel32.NewProc("OpenProcess")
	procVirtualAllocEx = kernel32.NewProc("VirtualAllocEx")
	procWriteProcessMemory = kernel32.NewProc("WriteProcessMemory")
	procCreateRemoteThread = kernel32.NewProc("CreateRemoteThread")
	procGetProcAddress     = kernel32.NewProc("GetProcAddress")
	procGetModuleHandle    = kernel32.NewProc("GetModuleHandleW")
	procCloseHandle        = kernel32.NewProc("CloseHandle")
	procVirtualFreeEx      = kernel32.NewProc("VirtualFreeEx")

	psapi              = syscall.NewLazyDLL("psapi.dll")
	procEnumProcesses  = psapi.NewProc("EnumProcesses")
	procGetModuleBaseName = psapi.NewProc("GetModuleBaseNameW")
	procEnumProcessModules = psapi.NewProc("EnumProcessModules")

	ntdll             = syscall.NewLazyDLL("ntdll.dll")
)

const (
	processAllAccess    = 0x1F0FFF
	memCommitReserve    = 0x3000
	pageReadWrite       = 0x04
	memRelease          = 0x8000
)

// findMinecraft enumerates running processes and returns the first one whose
// executable name looks like a Minecraft JVM.
func findMinecraft() (*Process, error) {
	var pids [1024]uint32
	var needed uint32
	r, _, err := procEnumProcesses.Call(
		uintptr(unsafe.Pointer(&pids[0])),
		uintptr(len(pids)*4),
		uintptr(unsafe.Pointer(&needed)),
	)
	if r == 0 {
		return nil, fmt.Errorf("EnumProcesses: %w", err)
	}
	count := needed / 4
	for i := uint32(0); i < count; i++ {
		name := processName(pids[i])
		if isMinecraftProcess(name) {
			return &Process{PID: pids[i], Name: name}, nil
		}
	}
	return nil, nil
}

func processName(pid uint32) string {
	h, _, _ := procOpenProcess.Call(0x0410, 0, uintptr(pid))
	if h == 0 {
		return ""
	}
	defer procCloseHandle.Call(h)
	var buf [256]uint16
	procGetModuleBaseName.Call(h, 0, uintptr(unsafe.Pointer(&buf[0])), uintptr(len(buf)))
	return syscall.UTF16ToString(buf[:])
}

func isMinecraftProcess(name string) bool {
	return name == "javaw.exe" || name == "java.exe"
}

// platformInject copies dllPath into a per-run temp directory and injects it
// into the target process via LoadLibraryW (matches the C++ launcher behaviour).
func platformInject(p *Process, dllPath string) error {
	// Create %TEMP%\slinky-<launcher-PID>\ and copy the DLL there so the DLL
	// can resolve sibling resources using its own module handle path.
	tmpDir := filepath.Join(os.TempDir(), fmt.Sprintf("slinky-%d", os.Getpid()))
	if err := os.MkdirAll(tmpDir, 0o700); err != nil {
		return fmt.Errorf("mkdir temp: %w", err)
	}
	destDll := filepath.Join(tmpDir, "SlinkyNative.dll")
	if err := copyFile(dllPath, destDll); err != nil {
		return fmt.Errorf("copy dll: %w", err)
	}

	hProc, _, err := procOpenProcess.Call(processAllAccess, 0, uintptr(p.PID))
	if hProc == 0 {
		return fmt.Errorf("OpenProcess(%d): %w", p.PID, err)
	}
	defer procCloseHandle.Call(hProc)

	path16, _ := syscall.UTF16FromString(destDll)
	size := uintptr(len(path16) * 2)

	addr, _, err := procVirtualAllocEx.Call(hProc, 0, size, memCommitReserve, pageReadWrite)
	if addr == 0 {
		return fmt.Errorf("VirtualAllocEx: %w", err)
	}
	defer procVirtualFreeEx.Call(hProc, addr, 0, memRelease)

	procWriteProcessMemory.Call(hProc, addr, uintptr(unsafe.Pointer(&path16[0])), size, 0)

	k32, _ := syscall.LoadDLL("kernel32.dll")
	loadLib, _ := k32.FindProc("LoadLibraryW")

	thr, _, err := procCreateRemoteThread.Call(hProc, 0, 0, loadLib.Addr(), addr, 0, 0)
	if thr == 0 {
		return fmt.Errorf("CreateRemoteThread: %w", err)
	}
	syscall.WaitForSingleObject(syscall.Handle(thr), syscall.INFINITE)
	procCloseHandle.Call(thr)
	return nil
}

func copyFile(src, dst string) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	out, err := os.Create(dst)
	if err != nil {
		return err
	}
	defer out.Close()
	_, err = io.Copy(out, in)
	return err
}

// platformFindLibBase finds the base address of a module loaded in the target process.
func platformFindLibBase(p *Process, name string) (uintptr, error) {
	hProc, _, err := procOpenProcess.Call(0x0410, 0, uintptr(p.PID))
	if hProc == 0 {
		return 0, fmt.Errorf("OpenProcess: %w", err)
	}
	defer procCloseHandle.Call(hProc)

	var mods [1024]uintptr
	var needed uint32
	procEnumProcessModules.Call(hProc,
		uintptr(unsafe.Pointer(&mods[0])),
		uintptr(len(mods)*8),
		uintptr(unsafe.Pointer(&needed)),
	)
	count := needed / 8
	name16, _ := syscall.UTF16FromString(name)
	for i := uint32(0); i < count; i++ {
		var buf [256]uint16
		procGetModuleBaseName.Call(hProc, mods[i], uintptr(unsafe.Pointer(&buf[0])), uintptr(len(buf)))
		if syscall.UTF16ToString(buf[:]) == syscall.UTF16ToString(name16) {
			return mods[i], nil
		}
	}
	return 0, fmt.Errorf("module %q not found in pid %d", name, p.PID)
}

// platformFindInitFunc resolves the bootstrap export by reading the PE export
// table from the target process's memory.
func platformFindInitFunc(p *Process, base uintptr) (uintptr, error) {
	// Simplified: assume the export "SlinkyInit" is at a known offset.
	// A full implementation would parse the PE export directory remotely.
	_ = base
	return 0, fmt.Errorf("not implemented: use LoadLibraryW path instead")
}
