---
name: mastercompiler-setup
description: >-
  Set up MasterCompiler (MC) as an MCP server for a terminal coding agent (Claude Code, Codex CLI, Grok Build,
  OpenCode) on Linux, macOS or Windows, with or without VS Code and Code for IBM i. Use when the user wants their
  CLI agent to build IBM i code, asks to install, update or connect MasterCompiler / MC, or MC's MCP tools (build,
  plan, impact, find_source, import_source, joblog) are missing. For Copilot in VS Code use mastercompiler-vscode.
  Triggers: set up MC, install MasterCompiler, MasterCompiler MCP, claude mcp add, codex config.toml, opencode.json,
  IBMI_PASSWORD_FILE, IBMI_PASSWORD_COMMAND.
---

# Set up MasterCompiler for a terminal agent

You may be reading this because the user pointed you at https://github.com/kraudy/MasterCompiler and asked to install
or try it. Goal: the `mastercompiler` MCP server is registered in your client, so you can call `plan`, `build`,
`impact`, `find_source`, `import_source`, `joblog` and `sql`. MC runs on this machine (the MCP server) and, over SSH,
on the IBM i: it uploads the repository's sources to `<home>/mc/<repository>`, compiles there and returns errors with
file and line. The local repository is the one you read and edit.

**Read the newest guide, not a cached one.** `raw.githubusercontent.com/.../master/...` can serve an older copy for
minutes. Get `tag_name` from `https://api.github.com/repos/kraudy/MasterCompiler/releases/latest` and read this guide at
`https://raw.githubusercontent.com/kraudy/MasterCompiler/<tag_name>/skills/mastercompiler-setup/SKILL.md`. Download the
jar and anything else at that same tag. (Copilot in VS Code: `skills/mastercompiler-vscode/SKILL.md` instead.)

## The password: never in the chat

There is no password box in a terminal client, so the password reaches MC from a **reference** the MCP entry names,
and the secret itself stays out of the chat, the command line, the repository and any file that is committed:

| `env` of the MCP entry | The password comes from |
|------------------------|--------------------------|
| `IBMI_PASSWORD_COMMAND` | a command whose first output line is the password, run once by MC: `secret-tool lookup service ibmi`, `pass show ibmi`, `op read op://vault/ibmi/password` |
| `IBMI_PASSWORD_FILE` | the first line of a file only its owner can read (`chmod 600`), outside the repository |
| (none) with an SSH key | leave the password out: MC signs on with the Code for IBM i connection's key, or `~/.ssh` keys when no password is set |

Prefer the command (a keyring) over the file. Do not put `IBMI_PASSWORD=<the password>` in a config file, and do not
ask the user to type it to you. If the user has neither, tell them to create the file themselves
(`mkdir -p ~/.config/mc && install -m 600 /dev/null ~/.config/mc/ibmi.pw`, then edit it in their own editor) and give you only its path.
MC never prints the password. After a rejected password MC stops signing on there (so the profile is not disabled): do
not retry and do not call other MC tools; see "After an authentication error".

## 1. Java

MC needs Java 8 or newer: `java -version`. If it is missing, ask the user before installing anything (a portable
Temurin JDK unpacked in the user's home works without root).

## 2. The jar

Keep it outside the repository, and always look the tag up again (the URL is fixed to the tag, so a new release only
arrives through a new lookup; replacing the file is how MC is updated):

```bash
mkdir -p ~/tools
tag=$(curl -fsSL https://api.github.com/repos/kraudy/MasterCompiler/releases/latest | sed -n 's/.*"tag_name": *"\([^"]*\)".*/\1/p')
curl -fL -o ~/tools/MasterCompiler.jar "https://github.com/kraudy/MasterCompiler/releases/download/$tag/MasterCompiler.jar"
java -jar ~/tools/MasterCompiler.jar --version      # must print: MasterCompiler <tag>
```

On 64-bit Windows the release also has `MasterCompiler-windows-x64.zip` with its own Java (see the
`mastercompiler-vscode` skill, step 2, for the PowerShell steps; the rest of this guide is the same there).

Optional, once per machine, so every repository's agent already knows MC (copies the skills, not the jar):
`java -jar ~/tools/MasterCompiler.jar --install-skills --agent <claude|agents|all>`
(`claude` = `~/.claude/skills`; `agents` = `~/.agents/skills`, read by Codex, Grok Build and OpenCode; `copilot` is
the default). Ask the user first.

## 3. What MC needs to know

- **Which IBM i**: with VS Code and Code for IBM i on this machine, `--code4i --connection "<name>"` reuses that
  connection (host, user, current library, library list, home directory); `MC_VSCODE_SETTINGS` names a `settings.json`
  in a non-standard place. Without them: `IBMI_HOSTNAME` and `IBMI_USERNAME` in the entry's `env`, and
  `--curlib <lib>` / `--libl "<libs>"` for where objects go. Ask the user for these values; check with
  `java -jar ~/tools/MasterCompiler.jar --doctor --code4i --connection "<name>"` (or the env variables): it prints host,
  user, transport and where the password would come from, never the password.
- **Where objects go**: builds replace objects in the current library. It must be the user's own development library,
  never a shared or production one. Preview before writing anything, and get the user's OK.
- **The repository**: the absolute path of the git repository (the MCP server runs with `--project <that path>`).
  Without a `build.yaml` MC scans the sources (see the `mastercompiler` skill).

## 4. Register the server (show the user the entry first)

`<abs repo>` is the absolute path of the repository; `<jar>` the absolute path of `MasterCompiler.jar`; `java` may be
a full path. Replace the `env` lines by the ones from "The password" that fit. With Code for IBM i, replace the
`IBMI_HOSTNAME` / `IBMI_USERNAME` env lines by `"--code4i", "--connection", "<name>"` in the arguments.
Do not commit a file that holds a user's host and paths: use the client's user/local scope, or a git-ignored file.

**Claude Code** (`claude mcp add --scope local|user|project`, or `.mcp.json` for the project):

```json
{ "mcpServers": { "mastercompiler": {
    "type": "stdio", "command": "java",
    "args": ["-jar", "<jar>", "--mcp", "--ssh", "--project", "<abs repo>"],
    "env": { "IBMI_HOSTNAME": "<host>", "IBMI_USERNAME": "<user>", "IBMI_PASSWORD_COMMAND": "secret-tool lookup service ibmi" } } } }
```

**Codex CLI** (`~/.codex/config.toml`, or `.codex/config.toml` in a trusted project; `codex mcp add` writes it):

```toml
[mcp_servers.mastercompiler]
command = "java"
args = ["-jar", "<jar>", "--mcp", "--ssh", "--project", "<abs repo>"]
cwd = "<abs repo>"
env = { IBMI_HOSTNAME = "<host>", IBMI_USERNAME = "<user>", IBMI_PASSWORD_COMMAND = "secret-tool lookup service ibmi" }
```

**Grok Build** (`~/.grok/config.toml`, or `.grok/config.toml`; `grok mcp add mastercompiler -- java -jar ...` adds it):
the same TOML table as Codex's `[mcp_servers.mastercompiler]` (`command`, `args`, `env`).

**OpenCode** (`~/.config/opencode/opencode.json` or the project's `opencode.json`; this is the long-standing flat
`mcp.<name>` shape, still accepted by current versions, whose newer native syntax nests under `mcp.servers`. Look at an
existing `mcp` entry in the user's file first and follow it):

```json
{ "mcp": { "mastercompiler": {
    "type": "local", "enabled": true,
    "command": ["java", "-jar", "<jar>", "--mcp", "--ssh", "--project", "<abs repo>"],
    "environment": { "IBMI_HOSTNAME": "<host>", "IBMI_USERNAME": "<user>", "IBMI_PASSWORD_COMMAND": "secret-tool lookup service ibmi" } } } }
```

Not sure of a key name for the user's client version? Use its own `mcp add` command or its docs rather than guessing.
This guide is for the SSH path (the port 22 Code for IBM i uses). The host servers (ports 449, 8470-8476) path is
set up by `--setup-vscode` for Copilot; a terminal agent uses SSH.

## 5. Start and verify

Restart the client (or reload its MCP servers, e.g. `/mcp` in Claude Code) so it starts the server. Then call `plan` without arguments: a list of targets means MC reached the IBM i and understood the project.
The first start over SSH uploads MC (about 10 MB) and takes longer. Then follow the `mastercompiler` skill for the
edit-compile-fix loop. If you cannot register or start the server yourself, give the user the exact entry and the
steps. Never write into their config without asking.

## After an authentication error

(`rejected the password`, `Auth fail`, `CPF22E2`, `CPF22E3`, `CPF22E4`.) Do not call another MC tool on that connection
and do not retry. Ask the user whether they want to change the password source (the keyring entry or the file), then
restart the MCP server in the client. `.mc/auth-blocked.json` in the project remembers a rejected password (a salted
hash): a new password gets one attempt. Only after a disabled profile or expired password (`CPF22E3` / `CPF22E4`), once
the security administrator fixed it, delete that file before restarting. Do not switch to running the jar from a shell
to get around the failure.

## Update, reinstall

Update: read `CHANGELOG.md` at the new tag, stop the client's MCP server, repeat step 2 (new tag lookup), check
`--version`, restart. Reinstall: delete the jar, repeat step 2, check `java` in the entry still exists. Do not delete the
project's `.mc/` folder (sources imported for reference). Nothing to copy to the IBM i: a new version uploads itself.

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| Tools missing after registering | Restart the client; check the entry's paths are absolute and `java -jar <jar> --version` works in a shell |
| `IBMI_HOSTNAME is not set` | Add it and `IBMI_USERNAME` to `env`, or use `--code4i --connection` |
| `VS Code user settings not found` | `--code4i` needs VS Code's `settings.json`: set `MC_VSCODE_SETTINGS`, or use `IBMI_HOSTNAME` / `IBMI_USERNAME` |
| `--ssh needs --project` | Give the absolute repository path after `--project` |
| host key changed | The IBM i's SSH key differs from `~/.ssh/known_hosts`. Do not work around it: ask the user to confirm with their administrator |
| Password command prints nothing | Run it in the user's own shell (not for you to read its output): the first output line must be the password |
