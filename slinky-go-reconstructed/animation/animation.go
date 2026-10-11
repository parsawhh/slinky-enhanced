package animation

// frames holds the four animation cels of the Slinky ASCII art.
// Each cel differs in the top coil marker (((  vs  //) and the
// bottom coil spread, producing a bouncing-spring illusion.
var frames = [4]string{
	// Frame 0 — coil spread outward, top ((
	"  _                            \n" +
		" ((                  ____      \n" +
		" \\\\________________( ( e\\____o\n" +
		" (                     _____/ \n" +
		"  \\___________________/       \n" +
		"  /   \\           \\  /        ",

	// Frame 1 — coil neutral, top ((
	"  _                           \n" +
		" ((                 ____      \n" +
		" ||________________( ( e\\____o\n" +
		" (                     _____/ \n" +
		"  \\___________________/       \n" +
		"   )  |           |   )       ",

	// Frame 2 — coil spread inward, top //
	"  _                          \n" +
		"  //                ____      \n" +
		" //________________( ( e\\____o\n" +
		" (                     _____/ \n" +
		"  \\___________________/       \n" +
		"   \\  /           /   \\       ",

	// Frame 3 — coil neutral inverted, top ((
	"  _                           \n" +
		" ((                 ____      \n" +
		" ||________________( ( e\\____o\n" +
		" (                     _____/ \n" +
		"  \\___________________/       \n" +
		"  |   )           )   |       ",
}

// roadFrames holds the road/ground animation that scrolls beneath the slinky.
var roadFrames = [2]string{
	"---->------>-------->",
	"-------->------>---->",
}

// Slinky returns the ASCII art frame at the given tick index.
func Slinky(tick int) string {
	return frames[tick%len(frames)]
}

// Road returns a scrolling road animation frame for the given tick.
func Road(tick int) string {
	return roadFrames[tick%len(roadFrames)]
}
