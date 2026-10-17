# Changelog

What changed in each release, for people updating MasterCompiler (MC) and for agents reading the setup guide.
Download any release from https://github.com/kraudy/MasterCompiler/releases.

## v0.3.22

From a team's feedback on deploying and testing with MC
- `run_command`: one CL command (SBMJOB, ENDJOB, ...) in MC's job with a preview and `confirm`; refused when it names
  a `protectedLibs` library or the spec has none; logged in `.mc/commands.log`. No more programs written only to run a command.
- `call_program`'s preview lists `otherEffects`: what the source names besides database writes (SBMJOB, ENDJOB, QSH,
  QCMDEXC, CALL, IFS commands, ...), and says those are not checked. An empty `writes` no longer reads as harmless.
- `upload_file` also takes files from the spec's `uploadSources` (folders on the PC besides the project); the preview
  shows the local path. `status` shows `uploadFolders`, `uploadSources` and `screenCcsid`.
- 5250 screens use the system's CCSID (QCHRID's code page) unless `ccsid` or `MC_5250_CCSID` says otherwise: accents
  show right on a non-37 system.
- Smaller reports: `plan` and `build` only count unchanged (skipped) targets (`skippedNote`); `showSkipped: true` lists
  them. A plan shows each hook's `timeout`.
- Targets taken out of the spec: MC records the objects it builds (`.mc/built-objects.json`); `plan` and `build` list the
  ones no target has any more in `formerTargets`, and `clean formerTargets: true` deletes them (with its preview).
- `sql`: a column that does not exist (SQL0206) comes back with the columns of the query's tables.
- 5250 hidden fields (a PASSCODE, a two-factor token, a password on a later screen): MC asks the user for the value
  in a password box and types it; the value never goes through the chat or the agent and is never logged or
  returned. `screen_send` takes `ask: [field numbers]`; a hidden field besides the password on the sign-on display
  is asked before Enter (an empty one is not a refused sign-on). The box is an MCP URL elicitation (a one-time page
  on 127.0.0.1) when the client offers it, else a dialog of MC's own. The Windows zip's Java adds `jdk.httpserver`.

## v0.3.21

From a team's feedback on long builds
- `build` no longer keeps the agent waiting: it waits up to `wait` seconds (20) and, if the build is still running,
  returns a `buildId` with the stage, target, command and seconds so far. `build_status` follows it (or returns the
  report once it ends). Tool calls run on threads of their own: `build_status`, `whats_running` and `cancel` are
  never queued behind a build; other tools that use the build job are refused while it runs (reads on a
  read-only connection still answer).
- `whats_running`: the build job seen from another connection: its status (RUN, MSGW, LCKW, DEQW, ...), call stack,
  the locks it waits for and the jobs holding them, the message it waits on and its last joblog lines.
- Hook `timeout: <seconds>` (and the spec's `hookTimeout`): past it, the job is looked at (the report's `timeouts`)
  and stopped (cancel, then ENDJOB *IMMED if needed); the target fails with `failedAt: timeout`.
- Inquiry messages in the build job get their default reply (`inquiryReply: "*DFT"`, CHGJOB INQMSGRPY), so a
  hook fails instead of waiting in MSGW. `*SYSRPYL` and `*RQD` can be chosen.
- `cancel`: with a preview and `confirm`, stops the running build on the IBM i (cancel, then ENDJOB *IMMED);
  nothing more of it runs. Stopping the call in VS Code did not stop the job.
- `screen_open` over TLS trusts the Windows trusted roots on Windows (the Windows zip's Java now has
  `jdk.crypto.mscapi`) and `MC_TRUSTSTORE` (a CA certificate or a key store).
- Agent instructions: say what a long call runs and how long it may take, report while a build runs, and say
  when something does not respond.
- Fixed: a `Cmd:` hook (or a command MC does not model) with a quoted value, e.g. `SNDMSG MSG('...')`, failed with
  SQL0104: its quotes are now doubled for QCMDEXC.

## v0.3.20

From a team's feedback on testing with MC
- RPGUnit results as JSON in the build report: each `RUCALLTST` hook gives a `tests` entry with the counts and,
  per failing test, its message, statement and the expected and actual values. MC asks RUCALLTST for its XML (a temporary file it removes) unless
  the hook names one; with an RPGUnit too old for `XMLSTMF`, the counts from its completion message.
- Compile errors apart from hook messages: `errors` and `joblog` are the compile's own; what hooks send (a test
  run's runtime messages) is in `hookJoblog`. `failedAt` says what failed: `compile`, `hook`, `tests` or
  `protectedLibs`.
- `protectedLibs` and hooks: the check runs after the target's `before` hooks (files they create are resolved
  again). A plan runs no hook: a write that resolves to a protected library now, with a hook naming the file,
  is reported instead of refused and checked again by the build. A refusal names the hook.
- `upload_file`: a project file to an IFS folder the spec allows (`uploadFolders`), with a preview (size,
  SHA-256, what it replaces) and `confirm`. Text files are tagged UTF-8. Logged in `.mc/uploads.log`.
- `call_program` `checkRows`: queries run before and after the call; the answer lists the rows inserted,
  deleted and changed, column by column.
- `spool`: list and read the user's own spooled files.
- Fixed: with `--push` and a scanned project (only `mc-base.yaml`), the job changed to the folder on the PC
  (CPFA0A9) instead of the pushed copy.

## v0.3.19

Agent instructions, second pass of the review
- Quick checks and the demo follow the first-build rule (`status` and `plan` with the user); `call_program` with
  its preview and `confirm`.
- `protectedLibs` described as it works: a build creates objects in the current library and fails a target that can
  change a file in a protected library; `call_program` and `copy_rows` refuse those libraries.
- `sql` limits with and without `format` in the skill and in `maxColumns`.
- Setup guide: the "own Java" block is not run after the Windows zip (it would reset `$java` / `$jar`);
  `docs/Copilot.md` sets both; the read-only password prompt template has the Reset Cached Inputs note.
- The "no credentials" error no longer suggests a `.env`; `docs/Cli.md` states the SSH key rule.

## v0.3.18

Agent instructions, from a review of what agents read
- In VS Code, MC's tools only: never a `.env`, the jar in a terminal, or the password in the chat. Queries go
  through `sql`, not the Db2 for IBM i extension (it signs on outside MC's guard).
- Before the first `build` of a session, `status` and `plan` with the user; tools that write only with `confirm`
  after the user agreed (also in the server's own instructions).
- After an authentication error no tool on that connection, `plan` included: ask, then the re-enter steps.
- Setup guide: `$java` / `$jar` set once (the Windows zip's paths), stop the server before replacing MC, the SSH
  key rule (with a password typed, keys in `~/.ssh` are not tried), the manual template's password prompt text.
- `maskColumns` and personal data: never another way around the mask; replace in `set` before `confirm`.
- 5250 only for display files; `sql` limits with and without `format`; `keepGoing` differs from the command line.
- `docs/Copilot.md` matches the guide (the zip, SSH by default, `import_source` instead of `--import`).

## v0.3.17

- `sql` `format: "inserts"`: columns Db2 generates in the query's table (`HAS_DEFAULT` in `QSYS2.SYSCOLUMNS2`:
  identity, row change timestamp, ROWID, row begin / end, transaction start id, generated expressions) are left out,
  so the statements insert; the file and the answer list them (`omittedGenerated`). `generated: "keep"` keeps
  identity values with `OVERRIDING SYSTEM VALUE`. GENERATED BY DEFAULT columns keep their values.

Fixes from a review
- Sign-on: attempts for one host and user run one at a time, so a 5250 `screen_open` (or a read-only connection to
  the same profile) during the SSH start-up cannot send the same wrong password alongside it.
- `SSH_MSG_DISCONNECT: 2` counts as a rejected password only when it is about authentication (code 2 is also a
  protocol error); a failed sign-on with no password (key only) is not recorded as one.
- 5250 sign-on: the sign-on display again (a hidden field where the password was, or the user field in its place and a
  hidden field anywhere) means refused; another display, even with a hidden field of its own, means signed on. The IBM i
  ending the session right after the password counts as a refusal.
- `sql`: before a query runs, Db2's own parse of it (`QSYS2.PARSE_STATEMENT`) must show no table it would change and,
  on a read-only connection, nothing outside its libraries and the catalogs (comments and quoted names no longer slip
  past the text check); `FINAL` / `NEW` / `OLD TABLE (` is refused; each `set` value must be one expression (no
  comments, no `;`, balanced parentheses), also `copy_rows`' `set` and `where`; `table` must be a table name. The
  text check sees through comments and quoted names; on a read-only connection a query Db2 cannot parse does not run.

## v0.3.16

Query results to a file
- `sql` with `format`: `csv`, `json`, `md` or `inserts` writes the whole result to `.mc/results/<file>` on the PC
  (every column, exact values, up to 10000 rows; a read-only connection's `maxRows` still caps it). The chat gets
  the file, the counts and the first rows. Also over SSH and on read-only connections.
- CSV: every value quoted, `"` doubled, NULL empty, UTF-8 with a BOM so Excel reads it. JSON: objects by column
  name, values as text (exact), a repeated name gets `_2`. Markdown: a table.
- `inserts`: INSERT statements for `table` (default: the query's only FROM table, unqualified) with typed
  literals: numbers exact, `DATE` / `TIME` / `TIMESTAMP '...'`, `X'...'` for binary, `'` doubled; 100 rows per
  statement. Made for RPGUnit setUp.
- `set` replaces columns on the system read (SQL expressions over the row), so replaced values never leave it;
  for `inserts` on a read-only connection, its `maskColumns` must be replaced.

## v0.3.15

- Windows download with its own Java: `MasterCompiler-windows-x64.zip` holds `MasterCompiler.jar` and a trimmed
  Java 21 runtime (`runtime\`), so nothing else is installed. Setup uses that runtime when it is next to the jar.
  The setup guide downloads it on 64-bit Windows; the plain jar stays for other systems and your own Java.
- Moving an existing setup to the zip: point the `mastercompiler` entry in `mcp.json` at
  `tools\MasterCompiler\runtime\bin\java.exe` and `tools\MasterCompiler\MasterCompiler.jar`.

## v0.3.14

5250 sessions (green screens)
- `screen_open`, `screen`, `screen_send`, `screen_close`: an interactive 5250 session on the build system from the
  PC, over TLS (port 992), MC's own client (no emulator needed). The agent reads the screen (lines, cursor, input
  fields with their labels), types into fields and presses Enter, F1-F24, PageUp / PageDown and so on.
- Sign-on: one attempt with the build connection's password, through the sign-on guard; a refused sign-on blocks
  this user's builds too (same profile). Preview first, then `confirm`.
- Hidden (password) fields are never shown and never typed into.
- Each screen is written to `.mc/screen.txt`, which you can keep open in VS Code to watch.

## v0.3.13

- A rejected password's error tells the agent to ask the user whether to type it again and lists the steps:
  "MCP: Reset Cached Inputs", "MCP: List Servers" > mastercompiler > "Restart Server", type it in VS Code's
  prompt (never in the chat). Same steps in both skills.

## v0.3.12

- Setup guide: "Update, reinstall, or re-enter the passwords": stop the server before replacing the jar
  (Windows keeps it open), when `--setup-vscode` helps, "MCP: Reset Cached Inputs" to type the passwords again.

## v0.3.11

Sign-on safety (a wrong password never disables the profile)
- Each sign-on is one attempt: over SSH the password goes once (no retry, not to both `password` and
  `keyboard-interactive`), and keys from `~/.ssh` are not offered when a password is given and the connection
  has no key of its own.
- After IBM i rejects a password (`CPF22E2`, `CPF2204`, `CPF22E5`, SSH `Auth fail`, ...) MC does not sign on
  to that host and user again in that run: every tool answers with the reason, and the server stays up.
- `.mc/auth-blocked.json` keeps the rejection (no password, only a salted hash): a restart with the same
  cached password does not sign on; another password gets one attempt; a successful sign-on clears it. A
  disabled profile or expired password (`CPF22E3` / `CPF22E4`) gets no attempt until the file is removed.
- `status` answers without a connection and lists every connection (build and read-only) with its state.
- `--setup-vscode` password prompts say to run "MCP: Reset Cached Inputs" after a password change.

## v0.3.10

- `copy_rows`, data from one system to another without `seeds.yaml`: rows of a table on a read-only connection
  inserted into the current library with their exact values; `set` replaces columns there (masked columns must be
  replaced), `deleteWhere` clears the target rows first, more than `maxRows` fails, preview then `confirm`,
  confirmed copies logged in `.mc/copies.log`.
- `maskColumns` also apply to `copy_rows`: those columns must be replaced in `set`.

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
