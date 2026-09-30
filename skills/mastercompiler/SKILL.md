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

Build the jar with `./mvnw clean package` (`target/MasterCompiler-1.0-SNAPSHOT.jar`).
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

## Commands

```bash
java -jar MC.jar -f build.yaml                       # build every target
java -jar MC.jar -f build.yaml --dry-run --json plan.json   # plan only: connects, compiles nothing
java -jar MC.jar -f build.yaml --json report.json    # build + machine-readable report
java -jar MC.jar -f build.yaml --diff                # changed sources + dependents only
java -jar MC.jar -f build.yaml -c                    # delete built objects at the end
```

Flags: `-x` debug, `-v` verbose (combine as `-xv`), `--no-migrate` (skip
member ↔ stream file migration), `--lib <name>` (library for scanned targets).

**Exit codes:** `0` success, `1` a target or command failed, `2` invalid arguments.

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

- `status`: `built`, `failed`, `skipped` (`--diff`, unchanged), `planned` (`--dry-run`),
  `not_built` (never reached: MC stops at the first failed target).
- `errors` come from the compiler's EVFEVENT member (needs `OPTION(*EVENTF)`, the
  default). `file` is relative to the spec's directory; errors inside copy members
  point at the copy member's own file and line.
- **Severity:** `00` informational (e.g. RNF7031 "not referenced"; many per
  compile, safe to ignore), `10` warning, `20` error that may still create the object,
  `30`+ errors that stop the compile. Fix `>= 30` first.
- A top-level `error` means a failure outside any target (global hook, connection).

## Edit–compile–fix loop

1. Edit sources in a local checkout.
2. Put them where the IBM i compiles from (`scp`/`rsync` over SSH, or `git pull` on
   the IBM i).
3. Run `java -jar MC.jar -f build.yaml --json report.json` (add `--diff` to limit it
   to what changed).
4. Exit code `0` → done. `1` → read the `failed` target's `errors` with severity
   `>= 30`, fix those lines, repeat. Use `joblog` when `errors` is empty (for
   example a binding or authority failure rather than a compile error).

## Gotchas

- **`-c` deletes every spec target that exists at the end, including objects that
  were already in the library before the build.** Before using it in a shared
  library, check that no existing object has a target's name, or build into a
  scratch library.
- **SQLRPGLE:** errors from the RPG step point at the precompiler's temporary member
  (`/QSYS.LIB/QTEMP.LIB/QSQLTEMP1.FILE/<name>.MBR`), not your source line. SQL
  precompile errors do point at the source.
- **`--diff` compares file modification times with object creation times.** A fresh
  `git clone` gives every file a new time, so the first `--diff` rebuilds everything.
- DDS targets compile from source members; MC migrates the stream file to a member
  first, so their errors name the member (`LIB/QDSPFSRC(NAME)`).
- Objects of types without `REPLACE` (PF, LF, BNDDIR, DTAARA, DTAQ, MSGF, SQL tables)
  are deleted and re-created when they already exist, which drops PF/table data.
