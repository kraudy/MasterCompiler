---
name: mastercompiler-vscode
description: >-
  Set up MasterCompiler (MC) as an MCP server for GitHub Copilot's agent mode in
  VS Code, reusing the developer's Code for IBM i connection, on Windows, macOS or
  Linux. Use when the user wants Copilot (or another agent) to build IBM i code,
  asks to connect or configure MasterCompiler / MC / the IBM i build tool, or MC's
  MCP tools (build, plan, impact, find_source, import_source, joblog) are missing. Triggers: set up MC,
  MasterCompiler MCP, mcp.json, Code for IBM i, Code4i, Copilot IBM i build.
---

# Set up MasterCompiler for Copilot in VS Code

You may be reading this because the user pointed you at https://github.com/kraudy/MasterCompiler
and asked to install or try it: this is the guide to follow with them, step by step.

**Read the newest guide, not a cached one.** If you fetched this file from `master`
(`raw.githubusercontent.com/kraudy/MasterCompiler/master/...`), that URL can serve an older copy
for several minutes. Get the latest release tag from
`https://api.github.com/repos/kraudy/MasterCompiler/releases/latest` (`tag_name`) and re-read this
guide at `https://raw.githubusercontent.com/kraudy/MasterCompiler/<tag_name>/skills/mastercompiler-vscode/SKILL.md`
before going on. Download everything else (jar, demo files) at that same tag: they then match.

Goal: the `mastercompiler` MCP server shows up in Copilot's agent mode, so you can call
`build`, `plan`, `impact`, `find_source`, `import_source` and `joblog`. MC runs on the developer's PC, uploads the
repository's sources to the IBM i (`--push`), compiles there and returns errors with file
and line. It reuses the Code for IBM i connection (host, user, current library, library list),
so the only thing the user types is their IBM i password, once, in a VS Code prompt.

**The password is entered only in VS Code's MCP prompt.** When the user starts the
`mastercompiler` server, VS Code shows a password box (the `ibmiPassword` input in
`.vscode/mcp.json`), stores the value in its secret storage and passes it to MC as `IBMI_PASSWORD`.
Never ask for the password in the chat, never have the user type it in a terminal (no
`$env:IBMI_PASSWORD = ...`, no `set IBMI_PASSWORD`, no command-line argument), and never write it
into `mcp.json`, `.env`, settings or any other file. Do not start MC yourself from a terminal to
test the connection: it would need the password there. Start the server from VS Code and test
with the `plan` tool. If the user pastes their password into the chat anyway, tell them not to and
to change it if the chat may be logged or shared.

Run the steps yourself when you can execute terminal commands (Copilot's Agent mode can; Ask
mode cannot: then suggest switching to Agent mode). When you cannot, or a step fails, give the
user the exact commands and explain what each one does. Never write into the repository without
asking first.

## 1. Java

```powershell
cmd /c "java -version 2>&1"
```

(`java -version` prints to stderr, which Windows PowerShell 5.1 shows as a red
`NativeCommandError` even when it worked; going through `cmd` avoids that.)

MC needs Java 8 or newer; a 64-bit Java is preferred. Setup picks the best Java it finds
(64-bit first, then one in a stable folder, then the newest) and shows it in the preview as "Java
for the server". The Red Hat Java extension's bundled Java is used only when there is no other
64-bit one: its folder name changes when the extension updates, which breaks `mcp.json` until
setup runs again (the preview warns). Offer a portable 64-bit JDK in that case. When `java` is not on PATH, look for one that is already installed
before asking the user to install anything:

- VS Code's Red Hat Java extension bundles one:
  `%USERPROFILE%\.vscode\extensions\redhat.java-*\jre\*\bin\java.exe`
  (macOS / Linux: `~/.vscode/extensions/redhat.java-*/jre/*/bin/java`).
- A JDK under `C:\Program Files\Java`, `C:\Program Files\Eclipse Adoptium` or `JAVA_HOME`.

Use the full path of that `java` in the next steps. If there is none: a portable JDK zip (for
example Eclipse Temurin) unzipped anywhere works without admin rights. Do not install software
system-wide without asking.

## 2. The MasterCompiler jar

Keep it outside the repository, e.g. in the user's tools folder:

```powershell
# Windows PowerShell 5.1 needs TLS 1.2; hiding the progress bar makes the download much faster
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$ProgressPreference = 'SilentlyContinue'
New-Item -ItemType Directory -Force "$env:USERPROFILE\tools" | Out-Null
$tag = (Invoke-RestMethod -UseBasicParsing https://api.github.com/repos/kraudy/MasterCompiler/releases/latest).tag_name
Invoke-WebRequest -UseBasicParsing -Uri "https://github.com/kraudy/MasterCompiler/releases/download/$tag/MasterCompiler.jar" -OutFile "$env:USERPROFILE\tools\MasterCompiler.jar"
```

The jar comes from the release tag (not a cached "latest" link), so `--version` must print
exactly that tag.

macOS / Linux: `curl -L -o ~/tools/MasterCompiler.jar <same URL>`.

Check the version:

```powershell
java -jar "$env:USERPROFILE\tools\MasterCompiler.jar" --version
```

It must print `MasterCompiler <tag>` with the tag from above (v0.3.4 or newer).
An error (`Unknown option: --version`) means a very old jar.

Always run the download above, even when `MasterCompiler.jar` is already there: the URL serves
the newest release, and replacing the file is how MC is updated (the `mcp.json` entry points at
this path, so nothing else changes; restart the `mastercompiler` server afterwards).

## 3. Configure the repository

**MC's tools work only in the VS Code window that has the repository open** (the server starts
with `--project ${workspaceFolder}`). If this chat runs in an empty window or another folder, the
work continues in a new window, and that window's chat knows nothing of this one. Hand it over:

1. Run the setup below on the repository folder with `--next "<the exact request>"`, e.g.
   `--next 'call import_source with into: "repo" and members: MYLIB/QRPGLESRC/ORD*,MYLIB/QDDSSRC'`.
   Setup saves it as `.github/prompts/mastercompiler-continue.prompt.md` (it creates the folder
   if needed), and MC mentions the waiting request when its server starts.
2. Open the folder: `code "<folder>"`.
3. Tell the user exactly what to do there, as numbered steps:
   1. In the new window, open Copilot Chat and pick **Agent** mode.
   2. Command Palette → **MCP: List Servers** → `mastercompiler` → **Start**, and type the IBM i
      password in VS Code's prompt.
   3. In the chat, type `/mastercompiler-continue` and press Enter.

   Also give them the full request as a ready-to-paste chat message, in case the prompt file does
   not show up. Do not try to call MC's tools from this window.

**Starting a repository from source members** (the user has members in source files, no
repository yet): create an empty folder and do the handover above with the `import_source` request
(`members` like `LIB/QRPGLESRC/ORD*,LIB/QDDSSRC`, or `objects`, and `into: "repo"`). It writes the
members with MC's names and they become the build. Do not use the command-line `--import` for
this: it needs the password in the terminal.

**No repository yet?** If the user has none open (or just wants to try MC first), offer MC's
small demo: four sources that build five objects, all named `MCD*` (a table, a module and its
service program, a binding directory, and an SQLRPGLE program that uses them). Users usually have
only one or two libraries and cannot create more, so the demo is kept small and is removed
afterwards.

```powershell
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$tag = (Invoke-RestMethod -UseBasicParsing https://api.github.com/repos/kraudy/MasterCompiler/releases/latest).tag_name
$demo = "$env:USERPROFILE\mc-demo"
New-Item -ItemType Directory -Force $demo | Out-Null
foreach ($f in 'MCDITEM.table.sql','MCDCALC.srvpgm.rpgle','mcdcalc_p.include.rpgle','MCDHELLO.pgm.sqlrpgle','mc-base.yaml','README.md') {
  Invoke-WebRequest -UseBasicParsing "https://raw.githubusercontent.com/kraudy/MasterCompiler/$tag/examples/demo/$f" -OutFile "$demo\$f"
}
code $demo
```

Continue in that window. Suggest `plan` first (compiles nothing), then `build`. When the user is
done, offer the `clean` tool to delete the five `MCD*` objects from their library: call it once
to list them, show the list, and only after the user agrees call it again with `confirm: true`.

Preview first, from the repository root; it writes nothing:

```powershell
java -jar "$env:USERPROFILE\tools\MasterCompiler.jar" --setup-vscode --project . --print
```

Show the user the preview and get their OK. It lists, per Code for IBM i connection, the
library objects will be created in (builds replace objects there: it must be a development
library), the library list, and the IBM i folder sources are uploaded to. Then run the same
command without `--print`. It:

- adds the `mastercompiler` server to `.vscode/mcp.json`, started with this same java and jar,
  keeping any other servers. If the file has comments it is not changed (rewriting would lose
  them); MC prints the entry to paste instead.
- installs MC's skills into `.github/skills/`.

With several Code for IBM i connections, the server asks which one to use each time it starts
(a `pickString` input), so the user can switch systems without running setup again;
`--connection "<name>"` fixes one. With no Code for IBM i connection, VS Code also asks for
host and user.

**SSH or host servers.** By default the server reaches the IBM i over SSH, the port Code for
IBM i already uses: MC uploads itself and the sources and runs on the IBM i. It logs in with the
user's SSH key (`~/.ssh/id_ed25519`, `id_ecdsa`, `id_rsa`, or the Code for IBM i connection's key)
or the password (empty when a key is used). The first start uploads MC (about 10 MB), so it takes
longer. `--host-servers` makes MC run on the PC and connect through the host servers instead
(ports 449, 8470–8476, like ACS).

If you cannot run commands but can create files: with the user's OK, read their Code for IBM i
connections (`code-for-ibmi.connections` in VS Code's user `settings.json`) and create
`.vscode/mcp.json` from the template under "Manual configuration", with the full paths of their
java and jar.

The server runs `--project ${workspaceFolder}`: MC uses `build.yaml` when the repository has
one, TOBi `Rules.mk` files when it is a TOBi / Bob project, and otherwise scans the sources.

## 4. Start the server (the user does this)

Tell the user:

1. Command Palette → **MCP: List Servers** → `mastercompiler` → **Start** (VS Code may ask
   to trust the server first).
2. Pick the connection (when there are several), then enter the IBM i password in the box
   VS Code shows. That box is the only place to type it: not the chat, not a terminal. VS Code
   stores it securely and passes it only to MC; it is never written to the repository.
3. Back in Copilot Chat (Agent mode), MC's tools are available.

Then check it works: call `plan` without arguments. A list of targets means MC reached the
IBM i and understood the project.

For SQL (table columns, data, the catalog), MC has no tool: check that the **Db2 for IBM i**
extension (`halcyontechltd.vscode-db2i`) is installed and suggest it if not. Its "Run SQL
statement" tool uses the same Code for IBM i connection.

## Manual configuration (when setup cannot run)

`.vscode/mcp.json`:

```json
{
  "inputs": [
    { "id": "ibmiConnection", "type": "pickString", "description": "Code for IBM i connection to build on",
      "options": ["CONNECTION 1", "CONNECTION 2"], "default": "CONNECTION 1" },
    { "id": "ibmiPassword", "type": "promptString", "description": "IBM i password", "password": true }
  ],
  "servers": {
    "mastercompiler": {
      "type": "stdio",
      "command": "C:\\path\\to\\java.exe",
      "args": ["-jar", "C:\\Users\\USER\\tools\\MasterCompiler.jar",
               "--mcp", "--code4i", "--connection", "${input:ibmiConnection}", "--project", "${workspaceFolder}"],
      "env": { "IBMI_PASSWORD": "${input:ibmiPassword}" }
    }
  }
}
```

With a single connection, drop the `ibmiConnection` input and put its name after `--connection`.

Without Code for IBM i, drop `--code4i --connection ...` and add `IBMI_HOSTNAME` and
`IBMI_USERNAME` to `env` (as values or as more `promptString` inputs).

## Where builds go

- Sources are uploaded to `<home directory>/mc/<repository folder>` on the IBM i (the home
  directory from Code for IBM i).
- Objects are created in the current library of the Code for IBM i connection, with its
  library list. Make sure that is the developer's own library, never a shared or production
  one; change it in Code for IBM i if needed.

## Troubleshooting

| Symptom | Cause / fix |
|---------|-------------|
| No MCP servers listed, or MCP disabled | The organization's Copilot policy may block MCP servers; the user must ask their admin. |
| `IBMI_PASSWORD is not set` | `mcp.json` lacks the password input or `env` entry; rerun `--setup-vscode`. Do not set the variable in a terminal instead. |
| `Several Code for IBM i connections` | Only when starting MC by hand: pass `--connection "<name>"` (setup's `mcp.json` asks at start). |
| `VS Code user settings not found` | Set `MC_VSCODE_SETTINGS` to the `settings.json` holding `code-for-ibmi.connections`, or configure without `--code4i`. |
| Connection refused / timeouts | Over SSH: the IBM i's SSH port must be reachable (Code for IBM i working from this PC proves it). With `--host-servers`: ports 449, 8470-8476 (or 9470-9476 with TLS), as for ACS. |
| `--ssh`: host key changed | The IBM i's SSH key differs from `~/.ssh/known_hosts`. Do not work around it: ask the user to confirm with their administrator. |
| `--ssh`: no key and no password | Enter the password when the server starts, or create an SSH key the IBM i accepts. |
| Java errors at start | Point `command` in `mcp.json` at a Java 8+ executable. |

Once MC's tools work, follow the `mastercompiler` skill for the edit–compile–fix loop.

## Keep these instructions for next time

If you read this guide from the web (the user gave you MasterCompiler's repository URL), offer to
save it as a personal skill, so future chats in any repository already know it. With the user's
OK, run:

```powershell
java -jar "$env:USERPROFILE\tools\MasterCompiler.jar" --install-skills
```

It copies MC's skills (this setup guide, the build loop, IBM i over SSH) into
`%USERPROFILE%\.copilot\skills`, where Copilot in VS Code loads them in every workspace.
