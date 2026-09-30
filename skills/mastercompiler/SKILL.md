---
name: mastercompiler
description: >-
  Build IBM i objects with MasterCompiler (MC): YAML build specs, --scan to
  generate a dependency-ordered spec, --dry-run plans, --diff incremental
  builds, and --json build reports with file/line compile errors for an
  edit-compile-fix loop. Use when compiling RPG/SQLRPGLE/CL/DDS/SQL/BND sources
  on IBM i, writing or fixing an MC spec, or reading an MC report. Triggers:
  MasterCompiler, MC, build spec, tobi.yaml, mc-base.yaml, CRTBNDRPG,
  CRTRPGMOD, CRTSRVPGM, EVFEVENT, compile errors, RNF, incremental build.
---

# MasterCompiler (MC)

MC compiles IBM i objects from a YAML spec, in the order the spec lists them. It
runs the real IBM i compilers (CRT* commands through `QCMDEXC`), so a green MC
build is a real build. For shell access to the IBM i itself see the
`ibm-i-pase` skill in this repo.

## Where MC runs

| Mode | How it connects | Needs |
|------|-----------------|-------|
| **On the IBM i** (PASE, over SSH) | Current job's user profile | Java 8+ in PASE; no credentials file |
| **Remote** (laptop, CI, container) | JT400 host servers | `.env` with `IBMI_HOSTNAME`, `IBMI_USERNAME`, `IBMI_PASSWORD` in the working directory |
| **Offline** (`--generate-only`) | none | Nothing; scan and spec writing only |

Get the jar from the latest release,
`https://github.com/kraudy/MasterCompiler/releases/latest/download/MasterCompiler.jar`
(`curl -LO` works in PASE too), or build it with `./mvnw clean package`
(`target/MasterCompiler-1.0-SNAPSHOT.jar`). The examples below call it `MC.jar`.
Running on the IBM i is the simplest agent path: copy the jar next to the sources and
run it over SSH. No password ever lands in a file.

## Target keys

A target is `library.object.objecttype.sourcetype`, e.g. `curlib.HELLO.pgm.rpgle`.
Object type + source type pick the command (`pgm.rpgle` → `CRTBNDRPG`,
`module.rpgle` → `CRTRPGMOD`, `srvpgm.bnd` → `CRTSRVPGM`, `pf.dds` → `CRTPF`,
`table.sql` → `RUNSQLSTM`, ...). `curlib` resolves to the job's current library.

## Spec shape

```yaml
defaults:            # params applied to every target (MC default: OPTION(*EVENTF))
  DBGVIEW: ALL
  OPTION: EVENTF
before:              # global CL before any target
  ChgCurDir:
    DIR: /home/USER/myrepo   # makes relative SRCSTMF paths resolve
targets:             # compiled in this order: dependencies first
  curlib.ART300.module.rpgle:
    params:
      SRCSTMF: QRPGLESRC/ART300.module.rpgle
  curlib.FARTICLE.srvpgm.bnd:
    params:
      SRCSTMF: QSRVSRC/FARTICLE.srvpgm.BND
      MODULE: [ART300]
  curlib.ART201.pgm.rpgle:
    before:          # per-target hooks: before / after / success / failure
      AddBndDirE: { BndDir: SAMPLE, Obj: FARTICLE }
    params:
      SRCSTMF: QRPGLESRC/ART201.pgm.rpgle
```

- **Relative `SRCSTMF` resolves against the IBM i job's current directory**, not the
  shell's. A hand-written spec with relative paths needs a global `ChgCurDir` to the
  source root in `before:`. `--scan` adds it for you.
- Params are CL keywords; values are flexible (`no`, `*NO`, `NO` all work).
- A full CRT* command can be pasted as `command:`; `params:` still wins.

## Generate a spec instead of writing one

```bash
java -jar MC.jar --scan <source-root> --generate-only -o build.yaml -v
```

Scan infers target keys from file names (`NAME.objtype.srctype`, e.g.
`FAM300.module.RPGLE`), finds dependencies (F-specs/`DCL-F`, `/COPY`/`/INCLUDE`
prototypes → service program exports, DDS `REF`/`PFILE`, `EXTPGM`, CL `CALL`,
SQL table refs, binding directories) and writes them in compile-safe order. It also
writes `build.cl`, the same plan as a CL command list. Values scan cannot infer
(binding directories, data areas, CMD `PGM`, hooks) go in `mc-base.yaml` at the scan
root. Copy members (`*.RPGLEINC`, `*.include.RPGLE`) are not targets.

## TOBi / Bob projects

```bash
java -jar MC.jar --from-tobi /home/USER/project -k --json report.json      # build it as it is
java -jar MC.jar --from-tobi project --generate-only -o build.yaml         # or convert once
```

MC reads the project's `Rules.mk` files and `iproj.json` (include path → `INCDIR`) and
builds the same objects TOBi would: object types come from the rules, rule dependencies
are added to MC's own. Targets MC cannot build (C, C++, COBOL, `CRTPGM` from modules,
panel groups, menus, system triggers, make recipes) are logged as `Skipped ...`; check
that list before relying on the build. Scanned file names may carry a TOBi description
after a dash (`ART200-Work_with_article.pgm.sqlrpgle` is `ART200`).

## Import an existing library

```bash
java -jar MC.jar --import MYLIB -o /home/USER/mylib-repo            # whole library
java -jar MC.jar --import "MYLIB/QRPGLESRC,MYLIB/QDDSSRC/ART*,OTHER" -o repo
```

Writes each member as `<SRCPF>/<name>` (one folder per library when several are
selected), plus `mc-import.json` and a scanned `build.yaml`. Names come from the objects
built from the members: OPM programs, DDS files and commands from the object
description, ILE programs and modules from `BOUND_MODULE_INFO`, binder source from
`PROGRAM_INFO`, SQL sources by object name. In the report, `how` says which:
`assumed` members had no object in the library (the scan treats them by source
type: `.rpgle` is a program), `copybook` members are `/COPY`d by others, `other`
members are not a type MC compiles. Review `assumed` before building. `/COPY FILE,MBR`
statements are not rewritten; they still resolve against the members on the system.

## Commands

```bash
java -jar MC.jar -f build.yaml                       # build every target
java -jar MC.jar -f build.yaml --dry-run --json plan.json   # plan only: connects, compiles nothing
java -jar MC.jar -f build.yaml --json report.json    # build + machine-readable report
java -jar MC.jar -f build.yaml -k --json report.json # keep going: every error in one run
java -jar MC.jar -f build.yaml --since origin/master # what git says changed + dependents
java -jar MC.jar -f build.yaml --push /home/USER/build --json report.json   # upload, then build
java -jar MC.jar -f build.yaml --diff                # sources newer than their objects + dependents
java -jar MC.jar -f build.yaml -c                    # delete built objects at the end
```

Flags: `-x` debug, `-v` verbose (combine as `-xv`), `--no-migrate` (skip
member ↔ stream file migration), `--lib <name>` (library for scanned targets).

**Exit codes:** `0` success, `1` a target or command failed, `2` invalid arguments.

**`-k`, `--keep-going`:** by default MC stops at the first failed target. With `-k` it
skips only that target's dependents (reported as `blocked`) and builds everything else,
so one run reports every broken target. The build still exits `1` when anything failed.

### Incremental builds

- `--since <git-ref>`: seeds are targets whose source file or any `/COPY`/`/INCLUDE`d
  file changed since the ref (committed, uncommitted and untracked files); every
  dependent is added. Use it in CI and agent loops. It assumes the objects from the
  ref's build already exist.
- `--diff`: compares source modification times with object creation times. A fresh
  clone makes every file look new, so prefer `--since` in CI.

### Building from a laptop or CI (`--push <ifs-dir>`)

Relative `SRCSTMF` paths are resolved on the IBM i, so the sources must be in the IFS.
`--push` uploads the local git repository (every tracked and non-ignored file, or only
the changed ones with `--since`) to `<ifs-dir>` over MC's own connection, keeping the
repository layout, as UTF-8 stream files. It then runs `CHGCURDIR` to the pushed copy
of the spec's directory before the spec's own `before:` hooks. Remote mode needs `.env`
(`IBMI_HOSTNAME`, `IBMI_USERNAME`, `IBMI_PASSWORD`). Do not also put a `ChgCurDir` in
the spec: it would run after MC's and point the job back at the old directory.

## The build report (`--json <file>`)

```json
{ "success": false, "built": 27, "failed": 1, "skipped": 0,
  "targets": [
    { "target": "MYLIB.ART201.PGM.RPGLE", "status": "failed",
      "command": "CRTBNDRPG PGM(*CURLIB/ART201) SRCSTMF('QRPGLESRC/ART201.pgm.rpgle') ...",
      "error": "Target compilation failed",
      "errors": [ { "file": "QRPGLESRC/ART201.pgm.rpgle", "line": 100, "column": 10,
                    "endLine": 100, "endColumn": 14, "id": "RNF7030", "severity": 30,
                    "message": "The name or indicator OPTXX is not defined." } ],
      "joblog": [ { "time": "...", "id": "RNS9310", "severity": 50, "text": "Compilation failed. ..." } ] },
    { "target": "MYLIB.ART202.PGM.RPGLE", "status": "not_built" } ] }
```

- `status`: `built`, `failed`, `skipped` (`--since`/`--diff`, unchanged), `planned` (`--dry-run`),
  `blocked` (`-k`: depends on a failed target), `not_built` (never reached: without `-k`
  MC stops at the first failed target).
- `errors` come from the compiler's EVFEVENT member (needs `OPTION(*EVENTF)`, the
  default). `file` is relative to the spec's directory (or the pushed copy of it);
  errors inside copy members point at the copy member's own file and line. For SQLRPGLE,
  errors from the RPG step are mapped from the precompiler's temporary member back to
  your source line; errors inside SQL-generated code point at the SQL statement's last
  line.
- **Severity:** `00` informational (e.g. RNF7031 "not referenced"; many per
  compile, safe to ignore), `10` warning. Anything above the compile's `GENLVL`
  (default 10) stops the compile, so **severity `20` and up are the errors to fix**.
  The compiler's final `RNS9308` summary record has line 0; skip it.
- Binder failures (`CPD5D02 Definition not found for symbol 'X'`) have no `file`: a
  procedure's module or service program is missing from the spec or its binding
  directory. `joblog` names the symbol.
- A top-level `error` means a failure outside any target (global hook, connection).

## MCP server

`java -jar MC.jar --mcp -f build.yaml` serves the spec over stdio (Model Context
Protocol). One IBM i job stays open across calls, and the spec is re-read on each call.

| Tool | Arguments | Returns |
|------|-----------|---------|
| `build` | `files` (changed sources, relative to the spec) or `since` (git ref); neither = everything. `keepGoing` (default `true`) | The build report above; `isError` when the build failed |
| `plan` | same as `build` | The report with `planned` targets; compiles nothing |
| `impact` | `object` (name or target key) | The object's targets and every dependent, in build order |
| `joblog` | none | The job's messages since the previous `joblog` call |

In VS Code with Copilot (Windows included), `java -jar MC.jar --setup-vscode --project .` writes
`.vscode/mcp.json` for you: MC runs on the PC with `--code4i` (the Code for IBM i connection:
host, user, library list) and `--project ${workspaceFolder}`, and VS Code prompts for the
password. See the `mastercompiler-vscode` skill.

Or run it **on the IBM i over SSH**, so it uses the SSH user's own job and no credentials
file (the login banner goes to stderr, so stdio stays clean):

```json
{ "mcpServers": { "mastercompiler": {
    "command": "ssh",
    "args": ["-S", "/tmp/ibmi.sock", "USER@HOST",
             "cd /home/USER/repo && java -jar MC.jar --mcp -f build.yaml 2>/dev/null"] } } }
```

Or **remotely** from the machine with the checkout, with `.env` in the working directory,
pushing sources before each build:

```json
{ "mcpServers": { "mastercompiler": {
    "command": "java",
    "args": ["-jar", "MC.jar", "--mcp", "-f", "build.yaml", "--push", "/home/USER/build"] } } }
```

## Edit–compile–fix loop

1. Edit sources in a local checkout.
2. Build with the sources where the IBM i compiles from:
   - remote: `java -jar MC.jar -f build.yaml --push /home/USER/build --since HEAD --json report.json`
   - on the IBM i (sources already in the IFS): `java -jar MC.jar -f build.yaml --since HEAD --json report.json`
3. Exit code `0` → done. `1` → read every `failed` target's `errors` with severity
   `>= 20` (use `-k` so one run shows them all), fix those lines, repeat. Use `joblog` when `errors` is empty (for
   example a binding or authority failure rather than a compile error).

## Gotchas

- **`-c` deletes every spec target that exists at the end, including objects that
  were already in the library before the build.** Before using it in a shared
  library, check that no existing object has a target's name, or build into a
  scratch library.
- DDS targets compile from source members; MC migrates the stream file to a member
  first, so their errors name the member (`LIB/QDSPFSRC(NAME)`).
- Objects of types without `REPLACE` (PF, LF, BNDDIR, DTAARA, DTAQ, MSGF, SQL tables)
  are deleted and re-created when they already exist, which drops PF/table data.
