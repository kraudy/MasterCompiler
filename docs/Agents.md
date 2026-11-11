# MasterCompiler with terminal agents (Claude Code, Codex, Grok, OpenCode) and Copilot

MC's agent side is an MCP server (`--mcp`) plus skills (`SKILL.md` files). Copilot in VS Code has its own one-command
setup ([Copilot.md](./Copilot.md)); terminal agents register the same server by hand or by asking the agent.

**Agents, before anything else:** read the setup guide at the latest release, not from `master` (its raw copy can be
cached for minutes). Get `tag_name` from `https://api.github.com/repos/kraudy/MasterCompiler/releases/latest` and read
`https://raw.githubusercontent.com/kraudy/MasterCompiler/<tag_name>/skills/mastercompiler-setup/SKILL.md`
(Copilot in VS Code: `skills/mastercompiler-vscode/SKILL.md`).

## Quick start

Give your agent the setup skill once, then ask it *"set up MasterCompiler for this repository"*.

```bash
mkdir -p ~/tools
tag=$(curl -fsSL https://api.github.com/repos/kraudy/MasterCompiler/releases/latest | sed -n 's/.*"tag_name": *"\([^"]*\)".*/\1/p')
curl -fL -o ~/tools/MasterCompiler.jar "https://github.com/kraudy/MasterCompiler/releases/download/$tag/MasterCompiler.jar"
java -jar ~/tools/MasterCompiler.jar --install-skills --agent all     # or: claude | agents | copilot, comma-separated
```

Or, with no jar yet, copy the setup skill of that release into your agent's skills folder (Claude Code shown):

```bash
curl -fL --create-dirs -o ~/.claude/skills/mastercompiler-setup/SKILL.md \
  "https://raw.githubusercontent.com/kraudy/MasterCompiler/$tag/skills/mastercompiler-setup/SKILL.md"
```

**Register the MCP server before you start the agent.** Clients load their MCP servers when they start: a server added
mid-session (`claude mcp add`, an edit to `opencode.json` or `config.toml`) has no tools until the client is restarted,
or, in Claude Code, reconnected with `/mcp`. Restart the MCP server the same way after you fix Java's CA store
(`cacerts`), change the password source, or delete MC's files on the IBM i from a shell (`--clean-remote`): it keeps
those from its start. MC's own `clean_remote` tool needs no restart.

## Where things go

| Client | Skills (`--agent`) | MCP server config | Password |
|--------|--------------------|-------------------|----------|
| Copilot in VS Code | `~/.copilot/skills` (`copilot`, the default), `.github/skills` | `.vscode/mcp.json`, written by `--setup-vscode` | VS Code's prompt |
| Claude Code | `~/.claude/skills` (`claude`) | `.mcp.json` or `claude mcp add` | `IBMI_PASSWORD_COMMAND` / `IBMI_PASSWORD_FILE` in the entry's `env` |
| Codex CLI | `~/.agents/skills` (`agents`) | `~/.codex/config.toml` | same |
| Grok Build | `~/.grok/skills` or `~/.agents/skills` (`agents`) | `~/.grok/config.toml` | same |
| OpenCode | `~/.config/opencode/skills` or `~/.agents/skills` (`agents`) | `opencode.json` | same |

`--agent all` installs to `~/.copilot/skills`, `~/.claude/skills` and `~/.agents/skills`; each client reads at least one of
them. `~` is Java's home folder; when the agent's home is another one (`$HOME` set elsewhere), name the folder instead:
`--install-skills --skills-dir "$HOME/.claude/skills"`. The exact entry for each client is in the setup skill at the
latest release tag, `https://github.com/kraudy/MasterCompiler/blob/<tag_name>/skills/mastercompiler-setup/SKILL.md`
(one place, so it stays in step with the release). Config file formats change between client versions: when in doubt, the client's own
`mcp add` command wins.

## The password

A terminal has no VS Code password box, and the MCP server's stdin is the protocol, so it cannot ask either. The entry's
environment configuration (`env`; OpenCode calls it `environment`) names where MC reads it (see [Cli.md](./Cli.md), "Passwords"):

- `IBMI_PASSWORD_COMMAND`: a command that prints it (`secret-tool lookup ...`, `pass show ...`, `op read ...`);
- `IBMI_PASSWORD_FILE`: a file only you can read (`chmod 600`), outside the repository;
- nothing, with an SSH key.

The agent never asks for it and it never goes in the chat, a command line or a committed file. After IBM i rejects a
password MC stops signing on there so the profile is not disabled; change the source and restart the MCP server.

## Interactive programs

`call_program` runs a program in a batch server job, with no display. A program with a display file (`WORKSTN`, e.g. a
menu or an inquiry screen) stops at the open of that file (`RNX1217`). Run those through the 5250 tools instead:
`screen_open`, then `screen_send` and `screen` (read the screen). A program that only shows `DSPLY` text returns nothing to
`call_program`: print to QPRINT, whose spooled output comes back in the answer, or return a parameter.

## No VS Code, no Code for IBM i

`IBMI_HOSTNAME` and `IBMI_USERNAME` in the entry's `env` replace `--code4i`; `--curlib` / `--libl` choose where objects go.
`--doctor` tests the connection and prints what MC would use (host, user, transport, where the password comes from),
never the password.

## MCP tools or the command line

With the `mastercompiler` server registered, use its tools. Without it, a terminal agent can run the jar directly
(`--scan`, `--dry-run`, `--json`, `--remote`; see the [mastercompiler skill](../skills/mastercompiler/SKILL.md)) with the
password from the same file or command. An authentication failure is never a reason to switch from one to the other.
