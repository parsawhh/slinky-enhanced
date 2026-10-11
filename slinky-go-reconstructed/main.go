package main

import (
	"fmt"
	"os"

	"github.com/slinkygg/loader/tui"
)

func main() {
	a := &app{}
	if err := tui.Run(a.run, a.runOnRender); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
