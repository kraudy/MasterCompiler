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

### Hooks, protected libraries, binding directories

```yaml
curlib: DEVLIB                 # library settings for the build job (CHGLIBL / CHGCURLIB before any hook);
libl: [DEVLIB, LIB1]         # also allowed alone in mc-base.yaml for scanned projects
protectedLibs: [LIB4]       # a program that can write to a file resolving here fails before compiling
before:
  - ChgLibl: { LibL: "DEVLIB LIB1" }       # also applied by plan, find_source, sql, ...
  - Cmd: CPYOBJS FROMLIB(LIB1) TOLIB(DEVLIB)   # any CL command, run as written
    ignore: [CPF2105]                        # like MONMSG: these messages do not fail the hook
  - DltObj: { Obj: "*CURLIB/WORK", ObjType: "*FILE" }
    ignore: [CPF2105]
targets:
  curlib.APPDIR.bnddir.bnddir:
    recreate: true               # delete and create it: only the spec's ADDBNDDIRE entries remain
```

- A **binding directory target** (`curlib.NAME.bnddir.bnddir`) is created once and then kept between
  builds; its `ADDBNDDIRE` hooks run on every build (an entry already there is not an error).
  Entries for project modules and service programs are qualified with the current library's name;
  other names keep what you wrote (unqualified is `*LIBL`). `recreate: true` deletes and creates it,
  so a wrong entry does not stick. Reports show, per target, which library each binding directory
  it uses resolves to (`bindingDirectories`), so an older copy earlier on the library list is visible.
- Commands MC knows are validated (params and values); other names (shop commands) are sent
  as written, so a misspelled command name only fails when it runs.
- Each RPG / COBOL target lists in `writes` the database files it can change (F-specs U/O/A,
  `dcl-f usage(*update/*output/*delete)`, `EXTFILE`, SQL INSERT / UPDATE / DELETE / MERGE) and the
  library each resolves to on the library list. With `protectedLibs`, a write there fails the
  target with the file named. Overrides (OVRDBF) and dynamic SQL are not seen.
- The report's `hooks` lists every hook command in order with its outcome (`ok`, `planned` in a
  plan, `ignored CPFxxxx`, `failed`). CHGLIBL / CHGCURLIB hooks also run in a plan, so a plan sees
  the spec's library list.

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

In VS Code with MC's MCP server running, use the `import_source` tool (`members`, `into: "repo"`)
instead: the server already has the password from VS Code's prompt. The command line below needs
the credentials in the environment (or MC running on the IBM i); an agent never asks for or types
the password in a terminal or the chat.

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
| `build` | `files` (changed sources, relative to the spec) or `since` (git ref); neither = everything. `keepGoing` (default `true`), `minSeverity` (default 20) | The build report above, compacted: `summary` per target first, messages below `minSeverity` and duplicates dropped, joblog severity 30+ in `errors`; `warnings` (e.g. two sources for one object); `isError` when the build failed |
| `plan` | same as `build` | The report with `planned` targets; compiles nothing |
| `impact` | `object` (name or target key) | The object's targets and every dependent, in build order |
| `clean` | `confirm` | Without `confirm`: lists the target objects that exist in the current library (`wouldDelete`) and deletes nothing. Show that list to the user; only after they agree call it again with `confirm: true`, which deletes them (dependents first) and their EVFEVENT members |
| `joblog` | none | The job's messages since the previous `joblog` call |
| `sql` | `statement`, `maxRows`, `maxColumns`, `connection` | Read-only query results: `columns`, `rows`, `rowCount`, `moreRows` when capped |
| `find_object` | `name`, `type`, `connection` | Libraries holding the object, `resolvesTo` on the library list, `authorized` per library |
| `copy_rows` | `connection`, `from`, `where`, `set`, `to`, `deleteWhere`, `confirm` | Copies rows from a table on a read-only connection into a table of the current library (see below). Without `confirm`: the rows, the first three and the statements; show them to the user, then call with `confirm: true` |
| `compare` | `object`, `type`, `connection` | The object on the build system and on a read-only connection side by side (library, create / change timestamps, per module the source member, the source change timestamp its compile recorded, the module's create timestamp), `differences` and `sameRecordedSource` |
| `status` | none | Version, IBM i user, current library and library list the tools use, project folder and spec, `protectedLibs`; over SSH also the host and the start-up stage. Call it first when something looks off |
| `call_program` | `program`, `parameters`, `confirm` | Runs a program the project builds (current library only) with typed parameters; returns the parameters as the program left them, `success` and messages. Refused when its source can write to a `protectedLibs` library, or writes files and the spec has no `protectedLibs`. Without `confirm`: what it would run and the files it can change |
| `find_export` | `symbol` | Service programs on the library list exporting it, binding directories that list them, project sources exporting it. First stop for `CPD5D02 Definition not found` |
| `find_source` | `objects` (`NAME`, `LIB/NAME`, optionally `NAME *TYPE`), `connection` | Where each object was compiled from (members per ILE module, stream files, binder source), its `/COPY` members, and a `status` per source: `ok`, `changed_since_compile` (may not match the object), `missing`. Reads only |
| `import_source` | `objects` and/or `members` (`LIB/SRCPF/MBR*`), `into`, `dryRun`, `refresh` | Copies those sources (and their copybooks) into the project with MC's names. `into: reference` (default) writes read-only copies to `.mc/sources/<LIB>/<SRCPF>/`, git-ignored and never built; `into: repo` adds them as build targets and keeps files already there (`kept`). Every file comes back with its project-relative `path`; anything missing is in `notImported` ("member X not found in LIB/SRCPF") while the rest is still imported; `copybooks` counts the copy members found. `dryRun: true` checks what exists and lists `wouldWrite` without writing; use it first when the member list came from the user |

### Other IBM i systems (read-only connections)

The spec can name the system builds go to and other systems the tools may only read, e.g. to check
what runs in production or to bring rows from it into the development library:

```yaml
connections:
  build: SYS1                  # Code for IBM i connection; the tools refuse to run on another one
  readOnly:
    - name: SYS2               # a Code for IBM i connection
      libraries: [LIB1, LIB2]  # the only libraries read there (also its library list)
      maxRows: 500             # most rows one query or copy reads there (default 1000)
      maskColumns: [COL3]      # sql shows them masked; copy_rows must replace them in set
```

- `sql`, `find_object` and `find_source` take `connection: SYS2` to read that system instead. Only
  its `libraries` (and the catalogs `QSYS2`, `SYSIBM`, ...) are read; the JDBC connection is read
  only. `build`, `call_program`, `clean` and `import_source` never run there. `maskColumns`
  hides those values in `sql` results (best effort: a column inside an expression is not masked).
- `compare` with `object` shows the object on both systems: whether the other system runs the
  source you changed (same source member and recorded source change timestamp per module).
- `copy_rows` reads rows there (`from`, `where`) and inserts them into a table of the current library on
  the build system (`to`, default the same name) with their exact values: numbers, dates and timestamps
  are not retyped or rounded. `set` replaces columns on the other system (SQL expressions, quote
  strings), so replaced values never leave it; `maskColumns` must be replaced. `deleteWhere` clears
  those rows in the target first, so copying again gives the same rows. Never into a `protectedLibs`
  library or outside the current library. More rows than `maxRows` fail instead of copying part.
  Preview first, then `confirm: true`; confirmed copies are logged in `.mc/copies.log`. When the
  other system holds personal data, the user's company rules may require replacing it.
- MC opens a read-only connection on first use. Its password comes from its own VS Code password
  prompt (`--setup-vscode` adds one per read-only connection in the spec); never ask for it in chat.

### Sources the project does not have

The build report's `external` lists what the sources use that the project does not build
(`"CUSTSRV *SRVPGM": [targets using it]`): called programs, bound service programs, files,
data areas. When you need one of them (a called program's parameters, a file's record format,
a copybook's data structure), call `find_source` with those names, then `import_source`,
and read the copies under `.mc/sources/`. Import with `into: "repo"` only when the user wants to
change that object: it then becomes part of the build and is compiled into their library.

### Test data

Test data belongs in the test that needs it, not in MC: an RPGUnit test (SQLRPGLE) inserts its rows
in `setUp` and deletes them in `tearDown`, so every run starts from the same data and leaves nothing
behind. The rows live in the developer's current library (where MC builds), never in a production
or `protectedLibs` library:

```rpgle
**free
ctl-opt nomain;
/include qinclude,TESTCASE           // RPGUnit's prototypes (assert, aEqual, iEqual, ...)

dcl-proc setUp export;
  exec sql set option commit = *none, naming = *sys;
  exec sql delete from TABLE1 where COL1 = 'T01';     // unqualified: the current library first
  exec sql insert into TABLE1 (COL1, COL2, COL3)
           values ('T01', 'test row', 12.50);
end-proc;

dcl-proc tearDown export;
  exec sql delete from TABLE1 where COL1 = 'T01';
end-proc;

dcl-proc test_reads_the_row export;
  dcl-s total packed(9:2);
  exec sql select COL3 into :total from TABLE1 where COL1 = 'T01';
  iEqual(0 : sqlcode);
  assert(total = 12.50 : 'COL3 of the test row');
end-proc;
```

- Keep test keys apart from real ones (a prefix such as `T`), and delete by those keys only, never a
  whole table.
- Insert every column the program reads, with literal values written in the test. To start from
  real rows, read them with the `sql` tool (also on a read-only connection) and write their values
  into the test, replacing personal data. To have real rows in the development library for a manual
  check, `copy_rows` brings them from a read-only connection.
- Build the test with the project like any other source, then compile and run it with the commands
  of the RPGUnit installed on that IBM i (`RUCALLTST` runs a test service program).

### Quick checks

Loop for logic changes: edit → `build` (changed files) → `call_program` with the parameters →
read the returned parameters and messages. A fixed-length message (a data structure) is one `char`
parameter of its full length: build the string with the fields at their positions.

### Db2 queries and objects

Use MC's `sql` tool for data and catalog questions (columns of a table, rows, which objects use a
file): one `SELECT` / `VALUES` / `WITH` per call on a connection the driver opens read only, with
the spec's library list, so unqualified names resolve the way the programs see them. Results are
capped (`maxRows`, default 100; `maxColumns`, default 40). It cannot change data; never try to work
around that. Handy catalog views: `QSYS2.SYSCOLUMNS2`, `QSYS2.SYSTABLES`, `QSYS2.SYSTABLEDEP`,
`QSYS2.OBJECT_STATISTICS`, `QSYS2.PROGRAM_INFO`, `QSYS2.SYSPARTITIONSTAT` (source members).

`find_object NAME *TYPE` lists every library holding an object, the one the library list resolves to
(`resolvesTo`) and your authority to each: check it before relying on an unqualified name.

The Db2 for IBM i VS Code extension (`#result`, `@db2i`) works too when it is installed.

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
- Objects of types without `REPLACE` (PF, LF, DTAARA, DTAQ, MSGF) are deleted and re-created
  when they already exist, which drops PF data; the report's `warning` says so. SQL tables are
  never dropped (use `CREATE OR REPLACE TABLE`, which keeps the rows; a plain `CREATE TABLE`
  fails when it exists), and an existing binding directory is kept (its `ADDBNDDIRE` hooks still run).
