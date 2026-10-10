# Changelog

What changed in each release, for people updating MasterCompiler (MC) and for agents reading the setup guide.
Download any release from https://github.com/kraudy/MasterCompiler/releases.

## v0.3.9

Removed
- The `seed` tool and `seeds.yaml`: test data belongs in the tests; the skill shows an RPGUnit test inserting its rows
  with SQL in setUp and removing them in tearDown.

Read-only connections
- `connections:` in the spec: `build` (tools refuse to run on another connection) and `readOnly` systems with the
  only `libraries` read there, `maxRows` and `maskColumns`.
- `sql`, `find_object`, `find_source` take `connection` to read a read-only system; a read-only MC runs there (over
  SSH, or in process over the host servers) with a read-only JDBC connection and no tool that writes.
- `compare`: one object on the build system and a read-only one side by side (create timestamps, per module the
  source member, recorded source change and module create timestamps), with the differences.
- `maskColumns`: `sql` shows those values masked.
- Each read-only connection's password comes from its own VS Code prompt (`IBMI_PASSWORD_<NAME>`); `--setup-vscode`
  adds the prompts, also to an existing `mcp.json`.

## v0.3.8

Tools
- `sql`: one read-only `SELECT` / `VALUES` / `WITH` per call (the driver opens the connection read only), with the
  spec's library list, row and column limits and a timeout.
- `find_object`: the libraries holding an object, the one the library list resolves to, and your authority to each.
- `find_export`: who exports a symbol (service programs on the library list, binding directories listing them,
  project sources), or everything one service program exports.
- `seed`: replays the project's `seeds.yaml` (copy rows with overrides, or one row) into the current library or
  `seedLibs` only, never into a `protectedLibs` library; preview first, then `confirm`.
- `call_program`: runs a program the project builds into the current library with typed parameters (char, packed,
  zoned, int) in its own job with the spec's library list and a time limit; refused when its source can write to a
  protected library; preview first, then `confirm`.
- `status`: version, IBM i user, current library and library list, project, spec, `protectedLibs`, SSH stage.
- `find_source`: bound service programs, binding directory entries, and where an object not on the library list is
  (or that you lack authority to it).

Spec and safety
- `protectedLibs`: each RPG / COBOL target lists the database files it can change and where they resolve; a write
  into a protected library fails the target before it compiles.
- `curlib:` / `libl:` keys; `mc-base.yaml` may hold only globals.
- Hooks: any CL command (`Cmd:` or unknown names, run as written), per-hook `ignore: [CPFxxxx]` like MONMSG.
- `recreate: true` for binding directories.
- CHGLIBL / CHGCURLIB hooks also run in `plan` and before the other tools, so they see the spec's library list.
- `BNDDIR` references default to `*LIBL` (only CRTBNDDIR creates in `*CURLIB`); a `BNDDIR` param orders the build;
  for SQLRPGLE it goes into `COMPILEOPT`.
- YAML errors give the line and column.

Reports
- Summary per target first; messages from severity 20 (`minSeverity`); duplicates removed; joblog severity 30+ in
  `errors`, severity 20+ promoted when a failed target has no compile error; CPD5D02 symbols listed per target,
  CPD5D1D as binder warnings, CPD5D09 dropped.
- `hooks` (every hook command and how it ended), `spec` (spec files and hash), `currentLibrary` / `libraryList`
  after the hooks, per target `writes` and `bindingDirectories` (where each resolves), `warnings` (two sources
  building one object). A failed global hook is reported with its command and messages.

Sync and import
- Full syncs remove files the project no longer has from MC's own folder on the IBM i; a changed spec is always
  uploaded, and targets defined only in it are rebuilt.
- Copy directives with column 1-6 markers (`D/COPY`, change markers), national characters; `NOMAIN` members import as
  `.module.*`, comment-only members as documentation, TXT copybooks as `.include.rpgle`; `refresh` for files already
  in the project; one note per kind instead of one per file.
- Setup adds `.mc/` to `.gitignore` and suggests `git init`; `--next-file` is removed once saved.

## v0.3.7
- `import_source`: a missing member is reported in `notImported` and the rest is still imported; each file's path;
  kept files downloaded to the PC; objects built from members looked for across the library list; copybook count;
  `dryRun`.

## v0.3.6
- SSH start-up logged stage by stage, upload progress, stalled transfers stopped with how far they got
  (`MC_STALL_SECONDS`), uploads size-checked before use, a broken jar on the IBM i uploaded again, hung remote commands
  time out (`MC_REMOTE_TIMEOUT`), a dead connection ends with a clear message.

## v0.3.5
- SSH start creates the project folder on the IBM i (empty repositories); `--next-file`; the connection is saved in the
  handover prompt; the setup guide is read at the release tag.

## v0.3.4
- Window handover: `--setup-vscode --next` saves the pending request as `/mastercompiler-continue`.
- Java: bitness read from the executable, stable installations preferred over the Java extension's folder.

## v0.3.3
- `-h` / `--help`, plain usage and setup preview; setup warns on `Q*` current libraries and picks the best Java.
- The IBM i password is typed only in VS Code's MCP prompt.

## v0.3.2
- `find_source` and `import_source`; the build report's `external` list; MCP logs show the commands MC runs.

## v0.3.1
- SSH answers `initialize` at once; many files go as one tar; `clean` lists first and deletes only with `confirm`;
  SQL tables are never dropped; existing binding directories are kept.

## v0.3.0
- Copilot + Code for IBM i: `--code4i`, `--setup-vscode`, MCP over SSH, the small `MCD*` demo, TOBi / Bob conversion.
