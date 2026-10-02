# MasterCompiler with GitHub Copilot and Code for IBM i

For teams that work on Windows with VS Code, [Code for IBM i](https://codefori.github.io/docs/) and ACS, and want
Copilot's agent mode to build and fix IBM i code. Nothing runs on Linux; MC runs on the PC next to VS Code.

```
Copilot (agent mode) --MCP--> MasterCompiler on the PC --host servers--> IBM i
   edits local files           uploads changed sources (--push)          compiles in your library
   reads errors      <--------  file / line / message report  <---------  EVFEVENT, joblog, listings
```

Code for IBM i and ACS stay as they are (browsing, 5250, spooled files). MC reuses the Code for IBM i
connection: host, user, current library, library list and home directory. The only extra input is the IBM i
password, which VS Code asks for once and stores securely.

## Setup (once per repository)

1. Java 8 or newer on the PC. A portable JDK zip works without admin rights.
2. Download the jar: `https://github.com/kraudy/MasterCompiler/releases/latest/download/MasterCompiler.jar`
   (for example into `%USERPROFILE%\tools`).
3. In the repository root, preview what setup would do (it writes nothing):
   ```powershell
   java -jar "$env:USERPROFILE\tools\MasterCompiler.jar" --setup-vscode --project . --print
   ```
   Check, for each Code for IBM i connection, the library objects go into (builds replace objects there) and the
   IBM i folder sources go to. Then run it without `--print`: it adds the `mastercompiler` server to
   `.vscode/mcp.json` (other servers are kept; a file with comments is left alone and the entry to paste is printed)
   and installs MC's agent skills into `.github/skills/`.
4. In VS Code: Command Palette → **MCP: List Servers** → `mastercompiler` → **Start**, pick the connection when you
   have several, and enter the IBM i password.

Or ask Copilot: *"set up MasterCompiler for this repository"*. The `mastercompiler-vscode` skill tells it how, and
what to ask you when it cannot do a step itself. To make that skill available before MC is set up, copy
[`skills/mastercompiler-vscode`](../skills/mastercompiler-vscode) into the repository's `.github/skills/`.

## SSH instead of the host servers

By default MC on the PC talks to the IBM i through the host servers (ports 449, 8470–8476), like ACS. Corporate
firewalls often block those while SSH (port 22, what Code for IBM i uses) is open. Then set it up with `--ssh`:

```powershell
java -jar "$env:USERPROFILE\tools\MasterCompiler.jar" --setup-vscode --project . --ssh --print
```

With `--ssh`, MC uploads itself and the project's changed sources over SSH and runs on the IBM i as your own job.
It logs in with your SSH key (`%USERPROFILE%\.ssh\id_ed25519`, `id_ecdsa` or `id_rsa`, or the key of the Code for
IBM i connection) or the password you enter when the server starts (leave it empty when you use a key).

## What the agent gets

| Tool | Use |
|------|-----|
| `plan` | What would compile, in order, and with which commands. Nothing is compiled. |
| `build` | Upload changed sources, compile them and everything that depends on them, return every error with file and line. |
| `impact` | What depends on an object. |
| `joblog` | The build job's recent messages. |

The server runs with `--project ${workspaceFolder}`: a `build.yaml` is used when present, a TOBi / Bob project
(`Rules.mk`) is converted, anything else is scanned.

## Where things go on the IBM i

- Sources: `<home directory>/mc/<repository folder>`.
- Objects: the current library of the Code for IBM i connection, with its library list. Use a personal development
  library, never a shared or production one.

## Requirements to check

- Your organization's Copilot policy must allow MCP servers in VS Code.
- The PC must reach the IBM i host servers (as ACS does).
- The sources must be in a git repository. If they are still in source members,
  `java -jar MasterCompiler.jar --import MYLIB -o <folder>` exports them with MC's naming and a `build.yaml`.
