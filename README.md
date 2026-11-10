**Quick start with Copilot:** in VS Code, switch Copilot Chat to **Agent mode** and say:

> set up MasterCompiler from https://github.com/kraudy/MasterCompiler

Copilot walks you through the rest. Details in [docs/Copilot.md](./docs/Copilot.md).
**Claude Code, Codex, Grok or OpenCode?** Same idea from the terminal: [docs/Agents.md](./docs/Agents.md). What changed in each release: [CHANGELOG.md](./CHANGELOG.md).

**Agents, before anything else:** read the setup guide at the latest release, not from `master` (its raw copy can be
cached for minutes and be out of date). Get `tag_name` from `https://api.github.com/repos/kraudy/MasterCompiler/releases/latest`
and read `https://raw.githubusercontent.com/kraudy/MasterCompiler/<tag_name>/skills/mastercompiler-vscode/SKILL.md`
(Copilot in VS Code) or `.../skills/mastercompiler-setup/SKILL.md` (Claude Code, Codex, Grok, OpenCode, any terminal agent);
download the jar and demo files at that same tag.

<div align="center">
<img src="./images/Master%20Compiler.jpg" alt="Master Compiler logo" style="display: block; margin: 0 auto; width: 40%; max-width: 800px; height: auto; object-fit: cover; aspect-ratio: 1 / 1;">
</div>

# Master Compiler

MasterCompiler is not **Tool X, but for IBM i**. It is an actual IBM I specific compilation tool built from first principles based on how the system handles object compilation. It is a completely different approach to answer the compilation sphinx.

YAML-based, sensible, open-source, modern, clean, and elegant approach to automate and streamline object compilation.

> **Setup guide for AI-assisted install:** `skills/mastercompiler-vscode/SKILL.md`, the step-by-step setup (Java,
> downloading MC, connecting it to Copilot as an MCP server with the Code for IBM i connection, a demo project, and
> what to confirm with the user before writing anything). For Claude Code, Codex, Grok or OpenCode:
> `skills/mastercompiler-setup/SKILL.md`.
>
> **Agents: read it at the latest release, not from `master`.** `raw.githubusercontent.com/.../master/...` can serve
> a cached, older copy for several minutes. Get the release tag, then fetch the guide (and the demo files) at that tag,
> which matches the jar you download:
> 1. `https://api.github.com/repos/kraudy/MasterCompiler/releases/latest` → `tag_name` (use it as it comes; do not reuse a version written in this text)
> 2. `https://raw.githubusercontent.com/kraudy/MasterCompiler/<tag_name>/skills/mastercompiler-vscode/SKILL.md`

---

MasterCompiler (**MC**) aims to give the whole IBM i community a standard way to describe the compilation flow of objects and allow easy integration with any DevOps pipeline to be run locally (just upload the JAR file) or remotely in a Docker container.

---

### Requirements 

Java 8.

---

### Download

```bash
curl -LO https://github.com/kraudy/MasterCompiler/releases/latest/download/MasterCompiler.jar
curl -LO https://github.com/kraudy/MasterCompiler/releases/latest/download/MasterCompiler.jar.sha256
sha256sum -c MasterCompiler.jar.sha256   # MasterCompiler.jar: OK
```

Runs anywhere Java 8+ is installed, including PASE on the IBM i. Every release is on the [releases page](https://github.com/kraudy/MasterCompiler/releases).

### Build 

* `git clone git@github.com:kraudy/MasterCompiler.git`
* `./mvnw clean package` (Maven wrapper: only a JDK 11 or newer is needed for the build and its tests, Maven downloads on
  first run; the jar it builds runs on Java 8+). The jar is
  `target/MasterCompiler-1.0-SNAPSHOT.jar`; the examples call it `MasterCompiler.jar`, the release's name.

### Test

Build official [TOBi recursive](https://github.com/IBM/tobi-example) example on real hardware on any server with zero proprietary tools, zero licensing and zero cost as showcase.

Only for a person running the integration tests by hand: an agent in VS Code never creates this file
and never asks for the password. Create a `.env` file like this

```bash
IBMI_HOSTNAME=BIGIRON.COM
IBMI_USERNAME=BIGBOY
IBMI_PASSWORD=BIGMONEY
```

Then, run the integration test to watch a thing of beauty. Note that this is the *heavy* test.

* `./mvnw clean verify -Pintegration-heavy`

[Tests doc](./docs/Tests.md#integration-tests)

---

## Object Compilation

To compile an object, it must be defined as a **target key** of the form: **mylib.hello.pgm.rpgle**.

| Target key | Library | Object name | Object type | Source type |
|----------|----------|----------|----------|----------|
| **mylib.hello.pgm.rpgle** | **MYLIB** | **HELLO** | `PGM` | `RPGLE` |


The **ObjectType** and **SourceType** part of the **Target key** defines the compilation command to be executed.

| Object type | Source type | Compilation command | 
|----------|----------|----------|
| `PGM` | `RPGLE` | `CRTBNDRPG` |


[Target keys docs](./docs/TargetKey.md)

[Compilation Patterns docs](./docs/Patterns.md) 

## Compilation Flow

Besides the compilation itself, an object often requires other CL commands to be performed on the same job to set up the appropriate environment:

* Library list
* Files overrides
* Binding directories, etc.

**MC** calls this a **compilation flow**. Which can be easily defined in a Yaml spec file.

[Specs doc](./docs/Spec.md)

```yaml
before:       # optional | Global pre-compilation system commands
  chglibl:                # Set library list before targets compilation
    libl: mylib1 mylib2 
  chgcurlib:              # Set curlib before targets compilation
    CURLIB: mylib1
  CrtBndDir:              # Lets create a bind dir
    BNDDIR: BNDHELLO

targets:      # Required | Ordered sequence of compilation targets. At least one target is required in the spec

  # First create the modules. We can specify curlib as the target library. Convenient.
  "curlib.modhello1.module.rpgle": 
    params:                # Per-target compilation command params
      SRCSTMF: /home/sources/hello2.modhello1.module.rpgle

  *curlib.modhello2.module.rpgle:
    params:
      SRCSTMF: /home/sources/modhello2.module.rpgle

  # Create service program with the modules
  "CurLib.srvhello.srvpgm.bnd":
    params:
      SRCSTMF: /home/sources/srvhello.srvpgm.bnd
      MODULE:
        - modhello1
        - modhello2

  # Ile rpgle with binding dir
  curlib.hello.pgm.rpgle:

    before:               # optional | Per-target pre-compilation system commands
      AddBndDirE:              # Add entry to bnd dir before target compiling
        BndDir: BNDHELLO
        Obj: SRVHELLO

    params:              
      # Target's compilation params
      SRCSTMF: /home/sources/HELLO.RPGLE
      DFTACTGRP: no
      ACTGRP: QILE
      STGMDL: Snglvl      # note how params names and values are flexible
      OPTION: EVENTF
      DBGVIEW: *source
      REPLACE: yes
      USRPRF: user
      TGTRLS: V7R5M0
      PRFDTA: nocol

after:        # optional | Global post-compilation system commands
  DltObj:                 # After all target are build, we delete the bind dir to have a clean slate
    OBJ: BNDHELLO
    OBJTYPE: BndDir
```

After defining the spec, just call **MasterCompiler** with unix style parameters

```bash
java -jar MasterCompiler.jar -xv -f /home/user/clean_spec.yaml
```

# Cli 

Unix base style Cli with short and long parameters.

Provide either a YAML spec `{-f, --file}` **or** a source root to scan `{--scan}`:

```bash
# Classic: hand-written YAML
java -jar MasterCompiler.jar -xv -f /home/user/clean_spec.yaml

# Scan sources → dependency order → generate YAML (and optionally build)
java -jar MasterCompiler.jar --scan /home/user/sources --generate-only -o build.yaml
java -jar MasterCompiler.jar --scan /home/user/sources -xv
```

A project folder works too (`--project <dir>` uses its `build.yaml`, a TOBi `Rules.mk` or a scan; `--spec <file>` picks
another YAML spec in it), `--target NAME` builds only the matching target(s) of that name in one library (`NAME.TYPE` narrows it), without their dependencies or dependents, to repeat it after a one-line edit.

[Cli doc](./docs/Cli.md) · [Scan doc](./docs/Scan.md) 

## Run and probe a program

`--call LIB/PGM` runs a program in one job with the library list you give (`--libl`, `--curlib`) and prints its
parameters, its messages and the spooled output of that job (`QPRINT`; the job log when the user can read it).
Parameters are typed JSON in
`--call-params`: `char`, `varchar` (an RPG `VARCHAR`, e.g. a JSON text), `packed`, `zoned` and `int`.

```bash
java -jar MasterCompiler.jar --call MYLIB/MYPGM --libl "MYLIB QGPL" \
  --call-params '[{"type":"varchar","length":2000,"value":"{\"id\":1}"},{"type":"int","bytes":4}]'
java -jar MasterCompiler.jar --sql "SELECT * FROM MYLIB.RESULTS"      # read what the probe wrote
```

A program that ends in error inside the call's server job (QZRCSRVS, owned by QUSER) does not have its stderr or
job-log line in the answer; the user's profile usually cannot read that job's log. A program that must report a
failure should write its details to a stream file (read with `--fetch`) or to `QPRINT`.

Over SSH, `--remote --project <dir> --call LIB/PGM` needs no host servers. The MCP `call_program` tool takes the same
parameters for programs the project builds in the current library, with a preview and a confirmation first; only the
command line `--call` also collects the job's spooled output. `debug_program` adds breakpoints and reads variables at
each stop. See the [Cli doc](./docs/Cli.md).

## Parameter resolution and validation

Params are enum based, this allows for automatic param conflict resolution and validation in O(1) time along with instant validation at deserialization.

Scan writes the **resolved** `params:` map (defaults + inspection + overlay), not just `SRCSTMF`, plus a paste-ready CRT* comment above each target. You can also paste a full CRT* command as `command:` (Docker-style array or CL string); MC parses it back into params. `params:` still wins.

[Params doc](./docs/Params.md) · [Spec doc](./docs/Spec.md)

## Source migration

MC encourages git usage by automatically migrating source members to ifs stream files and stream files to source members for OPM objects. 

Can be disabled with flag `--no-migrate`

[Migrator doc](./docs/Migration.md)

## Object inspection

If available, metadata is extracted from compiled objects to infer compilation params. The same inspection is applied when generating YAML if IBM i is connected.

[Object Descriptor doc](./docs/Inspection.md) 

## Traceability

Fully transparent and traceable flow of execution and changes.

[Traceability docs](./docs/Traceability.md)

## Copilot and Code for IBM i

Teams on Windows with VS Code and Code for IBM i can let Copilot's agent mode build and fix IBM i code with MC,
reusing their Code for IBM i connection. Once per PC, give Copilot MC's setup skill (PowerShell, no Java needed):

```powershell
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
New-Item -ItemType Directory -Force "$env:USERPROFILE\.copilot\skills\mastercompiler-vscode" | Out-Null
$tag = (Invoke-RestMethod -UseBasicParsing https://api.github.com/repos/kraudy/MasterCompiler/releases/latest).tag_name
Invoke-WebRequest -UseBasicParsing "https://raw.githubusercontent.com/kraudy/MasterCompiler/$tag/skills/mastercompiler-vscode/SKILL.md" -OutFile "$env:USERPROFILE\.copilot\skills\mastercompiler-vscode\SKILL.md"
```

Then, in any repository, ask Copilot in **Agent mode**: *"set up MasterCompiler for this repository"*. It checks Java,
downloads MC (on Windows with its own Java, nothing else to install), previews the setup with you and writes
`.vscode/mcp.json`. See [docs/Copilot.md](./docs/Copilot.md).

The agent can also open a 5250 session (green screen) to run and check display file programs: MC signs on over TLS
(port 992), reads the screens, fills fields and presses keys, and writes each screen to `.mc/screen.txt` so you can
watch it in VS Code.

## Agent skills

Skills for AI coding agents live in [`skills/`](./skills):

* [`mastercompiler`](./skills/mastercompiler/SKILL.md): specs, scan, `--dry-run`, `--json` reports and the edit-compile-fix loop.
* [`mastercompiler-vscode`](./skills/mastercompiler-vscode/SKILL.md): setting MC up as an MCP server for Copilot in VS Code.
* [`mastercompiler-setup`](./skills/mastercompiler-setup/SKILL.md): setting MC up as an MCP server for Claude Code, Codex, Grok Build and OpenCode.
* [`ibm-i-pase`](./skills/ibm-i-pase/SKILL.md): working on an IBM i over SSH/PASE (CL, SQL, IFS, CCSID, EVFEVENT).

`java -jar MasterCompiler.jar --install-skills [--agent copilot|claude|agents|all]` copies them into your agent's skills
directory (`~/.copilot/skills` by default, `~/.claude/skills`, `~/.agents/skills` for Codex, Grok and OpenCode), or copy
a skill folder yourself. See [docs/Agents.md](./docs/Agents.md).

## Contributing

Here is the rule: **You want to reduce complexity, increase readability, and provide functionality.**

Everything we do is a reflection of ourselves; even the world as we know it is a reflection of humanity... But that's a talk for another time.
