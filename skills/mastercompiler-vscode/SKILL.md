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

Goal: the `mastercompiler` MCP server shows up in Copilot's agent mode, so you can call
`build`, `plan`, `impact` and `joblog`. MC runs on the developer's PC, uploads the
repository's sources to the IBM i (`--push`), compiles there and returns errors with file
and line. It reuses the Code for IBM i connection (host, user, current library, library list),
so the only thing the user types is their IBM i password, once, in a VS Code prompt.

Run the steps yourself when you can execute terminal commands. When you cannot, or a step
fails, give the user the exact commands and explain what each one does.

## 1. Java

```powershell
java -version
```

MC needs Java 8 or newer. If it is missing, tell the user: a portable JDK zip (for example
Eclipse Temurin) unzipped anywhere works without admin rights; then use its `bin\java.exe`
by full path in the next steps. Do not install software system-wide without asking.

## 2. The MasterCompiler jar

Keep it outside the repository, e.g. in the user's tools folder:

```powershell
New-Item -ItemType Directory -Force "$env:USERPROFILE\tools" | Out-Null
Invoke-WebRequest -Uri https://github.com/kraudy/MasterCompiler/releases/latest/download/MasterCompiler.jar -OutFile "$env:USERPROFILE\tools\MasterCompiler.jar"
```

macOS / Linux: `curl -L -o ~/tools/MasterCompiler.jar <same URL>`.

## 3. Configure the repository

From the repository root:

```powershell
java -jar "$env:USERPROFILE\tools\MasterCompiler.jar" --setup-vscode --project .
```

It writes `.vscode/mcp.json` (the `mastercompiler` server, started with this same java and
jar) and installs MC's skills into `.github/skills/`. It prints which Code for IBM i
connection it used. If the user has several, it stops and lists them: rerun with
`--connection "<name>"`. With no Code for IBM i connection, VS Code will also ask for host
and user.

The server runs `--project ${workspaceFolder}`: MC uses `build.yaml` when the repository has
one, TOBi `Rules.mk` files when it is a TOBi / Bob project, and otherwise scans the sources.

## 4. Start the server (the user does this)

Tell the user:

1. Command Palette → **MCP: List Servers** → `mastercompiler` → **Start** (VS Code may ask
   to trust the server first).
2. Enter the IBM i password when prompted. VS Code stores it securely and passes it only to
   MC; it is never written to the repository.
3. Back in Copilot Chat (Agent mode), MC's tools are available.

Then check it works: call `plan` without arguments. A list of targets means MC reached the
IBM i and understood the project.

## Manual configuration (when setup cannot run)

`.vscode/mcp.json`:

```json
{
  "inputs": [
    { "id": "ibmiPassword", "type": "promptString", "description": "IBM i password", "password": true }
  ],
  "servers": {
    "mastercompiler": {
      "type": "stdio",
      "command": "C:\\path\\to\\java.exe",
      "args": ["-jar", "C:\\Users\\USER\\tools\\MasterCompiler.jar",
               "--mcp", "--code4i", "--connection", "CONNECTION NAME", "--project", "${workspaceFolder}"],
      "env": { "IBMI_PASSWORD": "${input:ibmiPassword}" }
    }
  }
}
```

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
| `Several Code for IBM i connections` | Rerun setup with `--connection "<name>"`. |
| `VS Code user settings not found` | Set `MC_VSCODE_SETTINGS` to the `settings.json` holding `code-for-ibmi.connections`, or configure without `--code4i`. |
| Connection refused / timeouts | MC uses the IBM i host servers (the same as ACS: ports 449, 8470-8476, or 9470-9476 with TLS). If ACS works from this PC, MC should too. |
| Java errors at start | Point `command` in `mcp.json` at a Java 8+ executable. |

Once MC's tools work, follow the `mastercompiler` skill for the edit–compile–fix loop.
