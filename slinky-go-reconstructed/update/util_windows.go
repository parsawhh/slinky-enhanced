package update

import (
	"io"
	"os"
	"syscall"
	"unsafe"
)

// replaceExecutable moves newBin over the currently running executable using
// a rename (which works atomically on Windows for same-drive moves) after
// scheduling the old file for deletion on next reboot via MoveFileExW.
func replaceExecutable(newBin string) error {
	self, err := os.Executable()
	if err != nil {
		return err
	}

	// Schedule old binary for delete on reboot (it is locked while running).
	selfW, _ := syscall.UTF16PtrFromString(self)
	moveFileEx := syscall.NewLazyDLL("kernel32.dll").NewProc("MoveFileExW")
	moveFileEx.Call(
		uintptr(unsafe.Pointer(selfW)),
		0, // null dest = delete
		0x4, // MOVEFILE_DELAY_UNTIL_REBOOT
	)

	// Write new binary next to the current exe and rename into place.
	tmp := self + ".new"
	if err := copyFile(newBin, tmp); err != nil {
		return err
	}
	return os.Rename(tmp, self)
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
