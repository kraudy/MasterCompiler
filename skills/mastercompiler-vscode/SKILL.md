---
name: mastercompiler-vscode
description: >-
  Set up MasterCompiler (MC) as an MCP server for GitHub Copilot's agent mode in
  VS Code, reusing the developer's Code for IBM i connection, on Windows, macOS or
  Linux. Use when the user wants Copilot (or another agent) to build IBM i code,
  asks to connect or configure MasterCompiler / MC / the IBM i build tool, or MC's
  MCP tools (build, plan, impact, joblog) are missing. Triggers: set up MC,
  MasterCompiler MCP, mcp.json, Code for IBM i, Code4i, Copilot IBM i build.
---

# Set up MasterCompiler for Copilot in VS Code

You may be reading this because the user pointed you at https://github.com/kraudy/MasterCompiler
and asked to install or try it: this is the guide to follow with them, step by step.

Goal: the `mastercompiler` MCP server shows up in Copilot's agent mode, so you can call
`build`, `plan`, `impact` and `joblog`. MC runs on the developer's PC, uploads the
repository's sources to the IBM i (`--push`), compiles there and returns errors with file
and line. It reuses the Code for IBM i connection (host, user, current library, library list),
so the only thing the user types is their IBM i password, once, in a VS Code prompt.

Run the steps yourself when you can execute terminal commands (Copilot's Agent mode can; Ask
mode cannot: then suggest switching to Agent mode). When you cannot, or a step fails, give the
user the exact commands and explain what each one does. Never write into the repository without
asking first.

## 1. Java

```powershell
java -version
```

MC needs Java 8 or newer. When `java` is not on PATH, look for one that is already installed
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
Invoke-WebRequest -UseBasicParsing -Uri https://github.com/kraudy/MasterCompiler/releases/latest/download/MasterCompiler.jar -OutFile "$env:USERPROFILE\tools\MasterCompiler.jar"
```

macOS / Linux: `curl -L -o ~/tools/MasterCompiler.jar <same URL>`.

Check the version:

```powershell
java -jar "$env:USERPROFILE\tools\MasterCompiler.jar" --version
```

It must print `MasterCompiler v0.3.0` or newer. An error (`Unknown option: --version`) means an
old jar that cannot do the setup below: download it again.

## 3. Configure the repository

**No repository yet?** If the user has none open (or just wants to try MC first), offer a demo
with IBM's TOBi sample project: an order-entry application with DDS files, RPG and SQLRPGLE
programs, modules, service programs, a binding directory, CL, commands and SQL. MC builds it
straight from its `Rules.mk` files (113 targets); 26 targets it cannot build (C, C++, COBOL,
programs bound from modules, panel groups, menus, triggers) are listed as skipped.

```powershell
git clone https://github.com/IBM/tobi-example.git "$env:USERPROFILE\mc-demo"
code "$env:USERPROFILE\mc-demo"
```

Continue in that window: ask for a `plan` first, which compiles nothing. Its objects go into the
user's current library, so make sure it is a development library. For a smaller demo, IBM's
company sample app (`https://github.com/IBM/ibmi-company_system.git`, 15 targets) works the same.

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
2. Pick the connection (when there are several), then enter the IBM i password. VS Code stores it securely and passes it only to
   MC; it is never written to the repository.
3. Back in Copilot Chat (Agent mode), MC's tools are available.

Then check it works: call `plan` without arguments. A list of targets means MC reached the
IBM i and understood the project.

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
| `IBMI_PASSWORD is not set` | `mcp.json` lacks the password input or `env` entry; rerun `--setup-vscode`. |
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
