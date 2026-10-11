# slinky-go-reconstructed

Reconstructed Go source code for `github.com/slinkygg/loader` — the Slinky
Minecraft cheat client launcher binary (`Slinky.exe`).

## How this was produced

The original binary was analysed with:
- `strings` / hex dump to extract embedded source file paths, function symbol
  names, string constants, and ANSI escape sequences
- Manual inspection of the animation frames stored in the `.rodata` section

No decompiler was used; this is a **clean-room reconstruction** written from
behavioural observation and symbol names only.

---

## Package layout

```
.
├── go.mod                    — module github.com/slinkygg/loader
├── main.go                   — entry point, wires app + tui
├── app.go                    — app struct: run() state machine + runOnRender()
├── animation/
│   └── animation.go          — Slinky() and Road() — all 4 animation frames
├── tui/
│   ├── tui.go                — Run(), UI struct, render loop, ANSI sequences
│   └── tui_windows.go        — Windows VT/ANSI console init (SetConsoleMode)
├── inject/
│   ├── inject.go             — FindMinecraft(), Process.Inject() interface
│   └── inject_windows.go     — LoadLibraryW injection via CreateRemoteThread
├── api/
│   └── api.go                — Client.GetLicenseRemainingDuration(), GetFile()
└── update/
    ├── update.go             — Check() — version query + download
    └── util_windows.go       — replaceExecutable() via MoveFileExW
```

---

## Key design notes for the redesign agent

### TUI / Animation

- The terminal uses the **alternate screen buffer**: `\x1b[?1049h` on start,
  `\x1b[?1049l` on exit — the user's shell history is never clobbered.
- Cursor is hidden: `\x1b[?25l` / `\x1b[?25h`.
- **Colour palette** (256-colour):
  - Orange `\x1b[38;5;208m` — all art + branding
  - Gray  `\x1b[38;5;247m` — status line
- In-place redraw uses `\r\x1b[<N>A` (carriage-return + cursor-up N lines) so
  the frame replaces itself without scrolling.
- Animation tick = 180 ms.

### Four animation frames (`animation/animation.go`)

Each frame is 6 lines. The top coil alternates between `((` and `//`;
the bottom coil cycles through four spread positions:

| Frame | Top  | Bottom spread              |
|-------|------|----------------------------|
| 0     | `((` | `  /   \           \  /`   |
| 1     | `((` | `   )  \|           \|   )`  |
| 2     | `//` | `   \  /           /   \`   |
| 3     | `((` | `  \|   )           )   \|`  |

### Status messages (in order)

1. `SCANNING`
2. `FOUND PID: <n>`
3. `INJECTING`
4. `READY`  **or**  `Please run Minecraft first`  **or**  `License expired`

### Branding line (shown at top, always)

```
Slinky Cracked by xMqrO     discord.gg/hitsconfigs
```

### Injection flow (Windows)

1. `EnumProcesses` → find `javaw.exe` / `java.exe`
2. `OpenProcess` → `VirtualAllocEx` → `WriteProcessMemory` (write DLL path)
3. `CreateRemoteThread` with `LoadLibraryW` as entry point
4. The loaded DLL bootstraps the Java agent via JNI / JVMTI

### API calls

- `GET /licence/check` → `{ "expires_in": <days> }` — shown as
  `"Your license expires in %.0f days"`
- `GET /files/<name>` → `{ "url": "...", "size": <MiB> }` — progress shown as
  `"%.1f MiB"`
- Headers: `X-License-Key`, `X-HWID`, `User-Agent: slinky-loader/1.0`
