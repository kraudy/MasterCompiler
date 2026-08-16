# Scan — auto-generate a YAML spec from sources

Instead of hand-ordering targets in a YAML file, point MasterCompiler at a **source root**. MC will:

1. Walk the tree and discover compileable sources  
2. Infer **target keys** from filenames  
3. Merge optional **base overlay** (`mc-base.yaml`) for non-inferable params  
4. Detect **dependencies** between targets  
5. **Topologically sort** so dependencies compile first  
6. Emit a YAML spec (and optionally compile it)

## CLI

```bash
# Generate ordered YAML only (no compile)
java -jar MasterCompiler-1.0-SNAPSHOT.jar \
  --scan /home/USER/sources \
  --generate-only -o build.yaml -v

# Scan and compile immediately (optional -o also writes the YAML)
java -jar MasterCompiler-1.0-SNAPSHOT.jar \
  --scan /home/USER/sources \
  --lib curlib -xv

# Classic path still works
java -jar MasterCompiler-1.0-SNAPSHOT.jar -f existing.yaml -xv
```

| Option | Meaning |
|--------|---------|
| `--scan <dir>` | Source root (local path or IFS). Mutually exclusive with `-f`. |
| `--base <file>` | Overlay YAML for non-inferable params (default: `<scan>/mc-base.yaml` if present). |
| `-o` / `--output <file>` | Write generated YAML here (also writes `<stem>.cl`, the local compile command list). |
| `--lib <name>` | Library segment for target keys (default: `curlib`). |
| `--generate-only` | Scan + write YAML + exit (requires `--scan` and `-o`). |

`--scan` without `--generate-only` connects to IBMi and runs the normal build on the generated in-memory spec.

## Base overlay (`mc-base.yaml`)

Scan cannot invent every CRT* parameter or object without a source member. Put **only non-inferable** values in a base file at the scan root:

```yaml
# mc-base.yaml — merged automatically on --scan
defaults:
  DBGVIEW: ALL
  OPTION: EVENTF
  REPLACE: YES

targets:
  # No source member — introduced by base only
  curlib.SAMPLE.bnddir.bnddir: {}

  curlib.LASTORDNO.dtaara.dtaara:
    params:
      Type: Dec
      LEN: 6 0
      VALUE: 60719

  curlib.CRTORD.CMD.cmd:
    params:
      PGM: ORD100          # required by CRTCMD; not valid on CMD definition source

  # Hooks on a scanned program
  curlib.ORD100.PGM.RPGLE:
    before:
      OvrDbf:
        File: tmpdetord
        ToFile: detord
        OvrScope: Job
      AddBndDirE:
        BndDir: SAMPLE
        Obj:
          - fvat
          - FCUSTOMER
```

Merge rules:

- Matching target keys: base `params` and hooks merge onto the scanned target. Scan keeps `SRCSTMF` when base omits it.  
- Target **only in base** (BNDDIR, DTAARA, DTAQ, MSGF, …): **added** to the build graph (no source required).  
- Global `defaults` / extra `before` from base are applied (scan still injects `CHGCURDIR` first).  
- Override path with `--base /path/to/overlay.yaml`.  

Generated `build.yaml` contains the **merged** result (ready to re-run with `-f`).

After merge, **MC** dry-resolves each target the same way compile does (MC defaults → object inspection when IBM i is connected → spec `defaults:` → target `params:` → conflict resolution) and writes the **filtered** param map. Identity values that only restate the target key (`PGM(*CURLIB/HELLO)`) and `SRCFILE`/`SRCMBR` when `SRCSTMF` is present are omitted. MC defaults that are not identity (`DBGVIEW`, `OPTION`, `REPLACE`, …) **are** written so the spec is a recipe.

`--generate-only` still works offline. Without a JDBC connection, inspection is skipped and the file gets defaults + scan + base. With `.env` / IBM i, existing objects contribute inspectable attributes (`ACTGRP`, `TGTRLS`, `USRPRF`, …). Inspection is not the original CRT* command; keywords such as `TGTCCSID` come from conflict resolution, not `PROGRAM_INFO`.

## Filename convention

Object type and source type are taken from the **basename** (not the folder):

```
{objectName}.{objectType}.{sourceType}   preferred
{objectName}.{sourceType}                object type defaulted
```

| File | Target key (with `--lib curlib`) |
|------|----------------------------------|
| `QRPGLESRC/FAM300.module.RPGLE` | `curlib.FAM300.module.rpgle` |
| `QRPGLESRC/ART200.pgm.sqlrpgle` | `curlib.ART200.pgm.sqlrpgle` |
| `QDDSSRC/ARTICLE.pf.dds` | `curlib.ARTICLE.pf.dds` |
| `QSRVSRC/FFAMILLY.srvpgm.BND` | `curlib.FFAMILLY.srvpgm.bnd` |
| `QRPGLESRC/ADDNUM.RPGLE` | `curlib.ADDNUM.pgm.rpgle` |

Defaults when object type is omitted:

- `rpgle` / `sqlrpgle` / `clle` / `rpg` / `clp` → `pgm`  
- `bnd` → `srvpgm`  
- DDS and SQL **require** the middle segment (`pf.dds`, `table.sql`, …)

Object name must be a valid IBM i name (1–10 chars: `A-Z 0-9 $ # @ _`).  
Descriptive middle tokens (e.g. `hello2.nomain.module.rpgle`) are ignored; the first segment is the object name.

Unrecognized files (`.md`, `.yaml`, `.RPGLEINC`, `*.include.RPGLE`, …) are skipped with a verbose log. Copy/include members are not compile targets. A consumer that `/copy`s or `/include`s them records the resolved path; `--diff` treats that file's mtime as part of the consumer (edit `ARTICLE.RPGLEINC` rebuilds ART201).

## Generated YAML shape

```yaml
# Generated by MasterCompiler --scan
# Root: /home/USER/sources
# Base directory: /home/USER/sources

before:
  CHGCURDIR:
    DIR: "/home/USER/sources"

targets:
  # CRTPF FILE(*CURLIB/ARTICLE) SRCFILE(*CURLIB/QDDSSRC) SRCMBR(ARTICLE) OPTION(*EVENTF)
  "CURLIB.ARTICLE.PF.DDS":
    params:
      SRCSTMF: "QDDSSRC/ARTICLE.pf.dds"
      OPTION: "*EVENTF"

  # CRTRPGMOD MODULE(*CURLIB/FAM300) SRCSTMF('QRPGLESRC/FAM300.module.RPGLE') OPTION(*EVENTF) DBGVIEW(*ALL) REPLACE(*YES) TGTCCSID(*JOB)
  "CURLIB.FAM300.MODULE.RPGLE":
    params:
      SRCSTMF: "QRPGLESRC/FAM300.module.RPGLE"
      OPTION: "*EVENTF"
      DBGVIEW: "*ALL"
      REPLACE: "*YES"
      TGTCCSID: "*JOB"

  "CURLIB.FFAMILLY.SRVPGM.BND":
    params:
      SRCSTMF: "QSRVSRC/FFAMILLY.srvpgm.BND"
      MODULE:
        - FAM300
      BNDSRVPGM: "*NONE"
      OPTION: "*EVENTF"
      REPLACE: "*YES"

```

- Targets appear in **compile-safe order** (dependencies first).  
- Each target gets relative `SRCSTMF` from the scan root plus the resolved non-identity compile params.  
- A `#` comment above the target is the **paste-ready CRT*** (IBM i command-line quotes, identity included). It is rebuilt from current `params:` every time the YAML is written. Edit params, then `-o` (or `--generate-only -o`) to refresh. Do not edit the comment by hand. DDS/OPM comments use `SRCFILE`/`SRCMBR`; the IFS path stays in `params`.  
- The same write emits **`build.cl`** next to `build.yaml`: global/target hooks, `CPYFRMSTMF` when the CRT* is member-based, then the CRT* line, in compile order. Not a `PGM`/`ENDPGM` member — a stream-file command list.  
- A global **`CHGCURDIR`** sets the IBM i job directory to the scan root so those relative `SRCSTMF` values resolve at compile time (same job as `QCMDEXC`).  
- Service programs get an inferred `MODULE` list when binder `EXPORT SYMBOL('…')` names match procedures exported from modules in the tree.  
- Commands get **`PGM`** from the base overlay (or hand YAML), never from the CMD definition member (`PGM` is not a valid keyword on the CMD statement). If that program is also a build target, the command depends on it so the program compiles first.

## What is not generated (v1)

Keep hand-written base overlay / full YAML (or edit the generated file) for:

- Objects without sources (BNDDIR, DTAARA, DTAQ, MSGF) and their create params  
- CRTCMD `PGM` and other params scan cannot discover (unless the `*CMD` already exists and inspection fills them)  
- Hooks (`AddBndDirE`, `OvrDbf`, `DltOvr`, `ADDMSGD`, …)  
- Library list / curlib / extra global defaults  

Object inspection still fills many params at build time when objects already exist. Scan also applies inspection when generating YAML if a JDBC connection is available, so the written spec already carries those values. YAML / base overlay params still win.

## How it fits with dependency awareness

`--diff` uses this graph: stale or missing objects are seeds; every father (dependent) is compiled too. Source last-edit is the stream file’s `File` / `IFSFile` mtime, not `IFS_OBJECT_STATISTICS`.

Scan reuses the same dependency scanners used by `--diff`:

- DDS `PFILE` / `REF` / `REFFLD`  
- RPG F-specs, `DCL-F`, `EXTPGM`, `DTAARA`, `EXTNAME`  
- `BNDDIR('A')` or `BNDDIR('A':'B':…)` on **programs** is a compile-order edge (comments ignored). On a `*MODULE` those names are recorded only and lifted onto the parent `*SRVPGM`. Not lifted when that srvpgm is already a member of the directory (would cycle).  
- Embedded SQL / `RUNSQLSTM` table refs  
- CL `CALL`  
- CL user-defined commands (project `*.cmd.cmd` names found as statement-leading tokens in CLP/CLLE)  
- CMD → processing program when `PGM` is set (base overlay / YAML) and that program is a build target  
- Program (and module) → service programs listed on `ADDBNDDIRE` hooks (consumer `before`/`after`)  
- BNDDIR consumers → srvpgms registered into that BNDDIR via any `ADDBNDDIRE` (including hooks on the \*SRVPGM itself)  
- Program or module → service programs whose **exports** match prototypes in `/copy` or `/include` (not whole-source scan; skips `extpgm` protos)  
- A \*MODULE never depends on a \*SRVPGM that lists it on `MODULE` (that edge already exists the other way and would cycle). Duplicate child/father links are ignored by `TargetKey`.  
- SRVPGM → modules via `MODULE` (explicit or inferred)  
- SRVPGM → other \*SRVPGMs its modules import via `/copy` `/include` prototypes. Written as inferred `BNDSRVPGM` when the overlay did not set one (so `CRTSRVPGM` can resolve those symbols). Explicit `BNDSRVPGM` in YAML / base is kept.

References to objects **outside** the scanned tree are ignored (same rule as deps outside a hand-written spec).

## Classes

| Class | Role |
|-------|------|
| [`SourceScanner`](../src/main/java/com/github/kraudy/compiler/SourceScanner.java) | Walk local FS or IFS |
| [`SourceNaming`](../src/main/java/com/github/kraudy/compiler/SourceNaming.java) | Filename → target identity |
| [`SpecGenerator`](../src/main/java/com/github/kraudy/compiler/SpecGenerator.java) | Orchestrate scan → deps → topo |
| [`SpecWriter`](../src/main/java/com/github/kraudy/compiler/SpecWriter.java) | BuildSpec → YAML |
| [`SpecResolver`](../src/main/java/com/github/kraudy/compiler/SpecResolver.java) | Dry-resolve params (defaults + inspect + spec) before write |
| [`CommandStringParser`](../src/main/java/com/github/kraudy/compiler/CommandStringParser.java) | Parse `command:` array / CL string into params |
| [`BuildTopoSort`](../src/main/java/com/github/kraudy/compiler/BuildTopoSort.java) | Dependency-first order |
| [`DependencyAwareness`](../src/main/java/com/github/kraudy/compiler/DependencyAwareness.java) | Graph + MODULE inference |
