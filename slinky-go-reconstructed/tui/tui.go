package tui

import (
	"fmt"
	"strings"
	"sync"
	"time"
)

const (
	orange = "\x1b[38;5;208m"
	gray   = "\x1b[38;5;247m"
	reset  = "\x1b[0m"
	bold   = "\x1b[1m"

	altScreenOn  = "\x1b[?1049h"
	altScreenOff = "\x1b[?1049l"
	hideCursor   = "\x1b[?25l"
	showCursor   = "\x1b[?25h"
	clearLine    = "\x1b[2K\r"
	moveUp       = "\x1b[1A"

	branding = "Slinky Cracked by xMqrO     discord.gg/hitsconfigs"

	artLines = 6 // number of lines in each animation frame
)

// UI holds the mutable state that runOnRender and run share.
type UI struct {
	mu     sync.Mutex
	status string
	art    string
}

// SetStatus updates the status line shown below the animation.
func (u *UI) SetStatus(s string) {
	u.mu.Lock()
	u.status = s
	u.mu.Unlock()
}

// DrawArt stores the ASCII art for the next render tick.
func (u *UI) DrawArt(art string) {
	u.mu.Lock()
	u.art = art
	u.mu.Unlock()
}

// WorkFunc is the background work function (injection logic etc.).
type WorkFunc func(ui *UI) error

// RenderCallback is called on every animation tick so the caller can update the art.
type RenderCallback func(ui *UI, frame int)

// Run initialises the terminal, starts the animation loop, runs work in a
// background goroutine, then tears down cleanly when work returns.
func Run(work WorkFunc, onRender RenderCallback) error {
	if err := platformInit(); err != nil {
		return err
	}
	defer platformCleanup()

	ui := &UI{status: "SCANNING"}

	fmt.Print(altScreenOn + hideCursor)
	defer fmt.Print(showCursor + altScreenOff)

	errCh := make(chan error, 1)
	done := make(chan struct{})
	go func() {
		defer close(done)
		errCh <- work(ui)
	}()

	tick := 0
	for {
		select {
		case <-done:
			onRender(ui, tick)
			render(ui, tick)
			time.Sleep(600 * time.Millisecond)
			return <-errCh
		default:
		}
		onRender(ui, tick)
		render(ui, tick)
		tick++
		time.Sleep(180 * time.Millisecond)
	}
}

// render redraws the whole screen in-place using \r + cursor-up sequences.
func render(ui *UI, tick int) {
	ui.mu.Lock()
	art := ui.art
	status := ui.status
	ui.mu.Unlock()

	var b strings.Builder

	// Move cursor to top-left of our block (artLines + 3 for padding/branding/status).
	totalLines := artLines + 3
	if tick > 0 {
		fmt.Fprintf(&b, "\r\x1b[%dA", totalLines)
	}

	// Branding header.
	b.WriteString(clearLine)
	b.WriteString(orange + bold + "  " + branding + reset + "\n")

	// Empty separator.
	b.WriteString(clearLine + "\n")

	// ASCII art (orange).
	for _, line := range strings.Split(art, "\n") {
		b.WriteString(clearLine)
		b.WriteString(orange + line + reset + "\n")
	}

	// Status line (gray).
	b.WriteString(clearLine)
	b.WriteString(gray + "  " + status + reset + "\n")

	fmt.Print(b.String())
}
