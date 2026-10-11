package main

import (
	"fmt"
	"os"
	"path/filepath"
	"time"

	"github.com/slinkygg/loader/animation"
	"github.com/slinkygg/loader/inject"
	"github.com/slinkygg/loader/tui"
)

type app struct{}

func (a *app) run(ui *tui.UI) error {
	// Locate SlinkyNative.dll next to this executable.
	exe, err := os.Executable()
	if err != nil {
		ui.SetStatus("Failed to locate executable")
		time.Sleep(3 * time.Second)
		return err
	}
	dllPath := filepath.Join(filepath.Dir(exe), "SlinkyNative.dll")
	if _, err := os.Stat(dllPath); err != nil {
		ui.SetStatus("SlinkyNative.dll not found")
		time.Sleep(3 * time.Second)
		return fmt.Errorf("SlinkyNative.dll missing")
	}

	ui.SetStatus("SCANNING")

	var proc *inject.Process
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		p, err := inject.FindMinecraft()
		if err == nil && p != nil {
			proc = p
			break
		}
		time.Sleep(500 * time.Millisecond)
	}

	if proc == nil {
		ui.SetStatus("Please run Minecraft first")
		time.Sleep(2 * time.Second)
		return fmt.Errorf("minecraft not found")
	}

	ui.SetStatus(fmt.Sprintf("Found Minecraft — PID: %d", proc.PID))
	time.Sleep(800 * time.Millisecond)

	ui.SetStatus("INJECTING ...")
	time.Sleep(500 * time.Millisecond)

	if err := proc.Inject(dllPath); err != nil {
		ui.SetStatus(fmt.Sprintf("Injection failed: %v", err))
		time.Sleep(3 * time.Second)
		return err
	}

	ui.SetStatus("Injected successfully")
	time.Sleep(600 * time.Millisecond)

	for i := 3; i >= 1; i-- {
		s := "s"
		if i == 1 {
			s = ""
		}
		ui.SetStatus(fmt.Sprintf("Closing in %d second%s", i, s))
		time.Sleep(1 * time.Second)
	}
	return nil
}

func (a *app) runOnRender(ui *tui.UI, frame int) {
	ui.DrawArt(animation.Slinky(frame))
}
