# MasterCompiler with GitHub Copilot and Code for IBM i

For teams that work on Windows with VS Code, [Code for IBM i](https://codefori.github.io/docs/) and ACS, and want
Copilot's agent mode to build and fix IBM i code. Nothing runs on Linux; MC runs on the PC next to VS Code.

```
Copilot (agent mode) --MCP--> MasterCompiler on the PC --SSH--> MasterCompiler on the IBM i
   edits local files           uploads changed sources          compiles in your library
   reads errors      <--------  file / line / message report  <---  EVFEVENT, joblog, listings
```

Code for IBM i and ACS stay as they are (browsing, 5250, spooled files). MC reuses the Code for IBM i
connection: host, user, current library, library list and home directory. The only extra input is the IBM i
password, which VS Code asks for once and stores securely.

Type the password only in that VS Code prompt, which appears when the `mastercompiler` server starts. Never type it
in the Copilot chat or a terminal, and never put it in `mcp.json`, `.env` or another file. VS Code keeps it in its
secret storage and hands it only to MC.

## Let Copilot do the setup

Copilot in VS Code loads personal skills from `%USERPROFILE%\.copilot\skills` in every workspace. Install MC's setup
skill there once per PC (PowerShell, no Java needed):

```powershell
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
New-Item -ItemType Directory -Force "$env:USERPROFILE\.copilot\skills\mastercompiler-vscode" | Out-Null
Invoke-WebRequest -UseBasicParsing https://raw.githubusercontent.com/kraudy/MasterCompiler/master/skills/mastercompiler-vscode/SKILL.md -OutFile "$env:USERPROFILE\.copilot\skills\mastercompiler-vscode\SKILL.md"
```

(With MC already downloaded, `java -jar MasterCompiler.jar --install-skills` installs all of MC's skills there.)

Then open the repository, switch Copilot Chat to **Agent mode** (other modes cannot run commands) and ask:
*"set up MasterCompiler for this repository"*. Copilot follows the skill: it finds Java, downloads MC, shows you the
`--print` preview (target library, library list, IBM i folder) and, once you agree, writes `.vscode/mcp.json`. You
then start the server and type your password; nobody types the steps below by hand.

## Setup by hand (once per repository)

1. Java 8 or newer on the PC. A portable JDK zip works without admin rights.
2. Download the jar (Windows PowerShell 5.1 included):
   ```powershell
   # Windows PowerShell 5.1 needs TLS 1.2; hiding the progress bar makes the download much faster
   [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
   $ProgressPreference = 'SilentlyContinue'
   New-Item -ItemType Directory -Force "$env:USERPROFILE\tools" | Out-Null
   Invoke-WebRequest -UseBasicParsing -Uri https://github.com/kraudy/MasterCompiler/releases/latest/download/MasterCompiler.jar -OutFile "$env:USERPROFILE\tools\MasterCompiler.jar"
   ```
   `java -jar "$env:USERPROFILE\tools\MasterCompiler.jar" --version` must print `v0.3.0` or newer.
3. In the repository root, preview what setup would do (it writes nothing):
   ```powershell
   java -jar "$env:USERPROFILE\tools\MasterCompiler.jar" --setup-vscode --project . --print
   ```
   Check, for each Code for IBM i connection, the library objects go into (builds replace objects there) and the
   IBM i folder sources go to. Then run it without `--print`: it adds the `mastercompiler` server to
   `.vscode/mcp.json` (other servers are kept; a file with comments is left alone and the entry to paste is printed)
   and installs MC's agent skills into `.github/skills/`.
4. In VS Code: Command Palette → **MCP: List Servers** → `mastercompiler` → **Start**, pick the connection when you
   have several, and enter the IBM i password in the box VS Code shows (the only place to type it).

Or ask Copilot: *"set up MasterCompiler for this repository"*. The `mastercompiler-vscode` skill tells it how, and
what to ask you when it cannot do a step itself. To make that skill available before MC is set up, copy
[`skills/mastercompiler-vscode`](../skills/mastercompiler-vscode) into the repository's `.github/skills/`.

## SSH or host servers

MC reaches the IBM i over SSH, the same port Code for IBM i uses: it uploads itself (once per version, to
`<home>/mc/.mc/`) and the project's changed sources, and runs on the IBM i as your own job. It logs in with your SSH
key (`%USERPROFILE%\.ssh\id_ed25519`, `id_ecdsa` or `id_rsa`, or the key of the Code for IBM i connection) or the
password you enter when the server starts (leave it empty when you use a key). The first start uploads MC itself
(about 10 MB); later starts only send changed sources.

If your network allows the host servers (ports 449, 8470–8476, the ones ACS uses) and you prefer them, set up with
`--host-servers`: MC then runs on the PC and connects like ACS.

## What the agent gets

| Tool | Use |
|------|-----|
| `plan` | What would compile, in order, and with which commands. Nothing is compiled. |
| `build` | Upload changed sources, compile them and everything that depends on them, return every error with file and line. |
| `impact` | What depends on an object. |
| `clean` | Delete the project's objects from your library (when you ask, e.g. after the demo). It first lists what it would delete; nothing is deleted until you confirm. |
| `joblog` | The build job's recent messages. |
| `find_source` | Where programs, service programs and files the project only uses were compiled from, with their copybooks. |
| `import_source` | Copy those sources into `.mc/sources/` to read them (or into the project, to change them). |

For SQL queries (table columns, data, catalog), Copilot uses the **Db2 for IBM i** extension's "Run SQL
statement" tool over the same Code for IBM i connection.

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
