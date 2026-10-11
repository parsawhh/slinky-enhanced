package tui

import (
	"fmt"
	"syscall"
	"unsafe"
)

var (
	kernel32                 = syscall.NewLazyDLL("kernel32.dll")
	procGetConsoleMode       = kernel32.NewProc("GetConsoleMode")
	procSetConsoleMode       = kernel32.NewProc("SetConsoleMode")
	procGetStdHandle         = kernel32.NewProc("GetStdHandle")
	procSetConsoleOutputCP   = kernel32.NewProc("SetConsoleOutputCP")
)

const (
	stdOutputHandle            = ^uintptr(10) // INVALID_HANDLE_VALUE trick; real = -11
	enableVirtualTerminalProc  = 0x0004
	enableProcessedOutput      = 0x0001
	utf8CodePage               = 65001
)

var (
	origMode uint32
	stdout   syscall.Handle
)

// platformInit enables ANSI/VT processing on the Windows console and sets
// the code page to UTF-8 so the art renders correctly.
func platformInit() error {
	h, _, err := procGetStdHandle.Call(uintptr(^uint32(10)))
	if h == 0 {
		return fmt.Errorf("GetStdHandle: %w", err)
	}
	stdout = syscall.Handle(h)

	procGetConsoleMode.Call(uintptr(stdout), uintptr(unsafe.Pointer(&origMode)))
	newMode := origMode | enableVirtualTerminalProc | enableProcessedOutput
	procSetConsoleMode.Call(uintptr(stdout), uintptr(newMode))
	procSetConsoleOutputCP.Call(utf8CodePage)
	return nil
}

// platformCleanup restores the original console mode.
func platformCleanup() {
	procSetConsoleMode.Call(uintptr(stdout), uintptr(origMode))
}
