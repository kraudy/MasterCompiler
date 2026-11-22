# Cli

Master compiler follows unix philosophy in various parts of its design. One of them is the CLI param validation.

* All params are validated at the start, so you don't have to insist later on interactive prompts.
* Params format follow the short, long syntax
* Short params can be combined

[Argument parser class](../src/main/java/com/github/kraudy/compiler/ArgParser.java) 

## Parameters

* Spec path **or** scan root (one required):
  * YAML file `{-f, --file}`
  * Source root to scan `{--scan}` — see [Scan.md](./Scan.md)
* Base overlay for non-inferable scan params `{--base}` (default: `<scan>/mc-base.yaml` if present)
* Output path for generated YAML `{-o, --output}` (also writes `<stem>.cl`)
* Default library for scanned targets `{--lib}` (default: `curlib`)
* Generate or rewrite YAML only, no compile `{--generate-only}` (requires `-o`, and either `--scan` or `-f`)
* Debug and verbose log output `{-x, -v, -xv}`
* Dry run execution allows to run the compiler without executing any commands, it follows the flow of execution and generates the command's strings. `{--dry-run}` A build's dry run still signs on to the IBM i: the commands depend on the job's current library and library list. To try MC with no
  IBM i at all, scan and write the spec only: `java -jar MasterCompiler.jar --scan <sources> --generate-only -o build.yaml`.
  (`--sql`, `--fetch` and `--call` with `--dry-run` print what they would do without connecting.)
* No migrate flag omits source files migration `{--no-migrate}`
* Incremental build `{--diff}`: compile targets whose source is newer than the object (or whose object is missing), plus every dependent. Source time is the newest of the stream file and any `/copy`/`/include` attachments (`File` / `IFSFile`). Does not `touch` sources.
* Clean deletes created objects after the build `{ -c, --clean }`
* Keep going `{-k, --keep-going}`: after a failed target, skip only its dependents (`blocked` in the report) and build everything else, so one run reports every broken target. Exit code is still `1` when anything failed. MCP `build` keeps going by default.
* Git incremental build `{--since <git-ref>}`: compile targets whose source or any `/copy`/`/include` attachment changed since the ref (committed, uncommitted and untracked files), plus every dependent. No timestamps, so fresh clones and branch switches only rebuild what changed. Cannot be combined with `--diff`.
* Project `{--project <dir>}`: use what the project has — `build.yaml` if present, else TOBi `Rules.mk` (as `--from-tobi`), else a scan. `--spec <file>` names another YAML spec inside the project folder to use instead of `build.yaml` (e.g. `--spec tobi.yaml`); it works with `--remote`, where the uploaded copy's file is used.
* Code for IBM i connection `{--code4i}` / `{--connection <name>}`: connect with the connection configured in Code for IBM i (VS Code user settings: host, user, current library, library list, home directory); the password comes from `IBMI_PASSWORD`. The build job gets that library list, and remote builds push to `<home>/mc/<project folder>` unless `--push` is given. `--connection` picks one of several connections.
* SSH `{--mcp --ssh [--project <dir>]}`: reach the IBM i over SSH (`--ssh-port` alone implies `--ssh`). Without `--project` the project is the client's folder when it is a repository (`build.yaml`, `Rules.mk` or a git repository, never the home folder), else none until the `open_project` tool names one; the way Code for IBM i does (port 22 instead of the host servers, which firewalls often block). On the PC, MC uploads its own jar once per version to `<home>/mc/.mc/`, syncs the project to `<home>/mc/<folder>` (only files whose size or time changed, tagged CCSID 1208; files the project no longer has, renamed, moved or deleted, are removed from that copy on every full sync, but never from a folder given with `--push`; git supplies the file list when it knows files in the project, and a project it does not know (ignored or untracked inside an enclosing repo) is uploaded from the folder itself), starts `java -jar MC.jar --mcp` on the IBM i, where it builds as the SSH user's job, and relays MCP. Before `build` / `plan` / `impact` it uploads what changed; a git `since` is turned into a file list. Login: the Code for IBM i connection's key, or the password (see Passwords below), sent once to a password prompt of the server (password or keyboard-interactive, whichever comes first; a prompt for a one-time code or several questions cancels the login). With a password and no key of the connection's own, the keys in `~/.ssh` are not tried (each would count as a failed sign-on); without a password, `~/.ssh` keys (`id_ed25519`, `id_ecdsa`, `id_rsa`) are. Host keys: unknown hosts are trusted and added to `~/.ssh/known_hosts`, changed keys are refused; with `--strict-host-key` (or `IBMI_SSH_KNOWN_HOSTS`, a known_hosts file of your own) an unknown host is refused too and its fingerprint is logged, for CI. With `--code4i` the host, port, user, home directory and library list come from Code for IBM i; otherwise `IBMI_HOSTNAME`, `IBMI_USERNAME` (environment or `.env`). SSH port: `--ssh-port` > the Code for IBM i connection's port > `IBMI_SSH_PORT` > 22 (PUB400 uses 2222). MC's own folders on the IBM i (`<home>/mc`, `.mc`, the project copy) are private to the SSH user (700/600), `.env`, `.env.*`, `.mc/`, keys and certificates (`*.pem`, `*.key`, `*.p12`, `*.pfx`, `*.jks`, `id_rsa`..., `.netrc`, `known_hosts`) are never uploaded even when git tracks them, and only text files are tagged CCSID 1208. The uploaded jar is checked by SHA-256 (`sha256sum` or `csum`; by size when neither exists) and Java 8+ is checked on the IBM i. `.mc/sync-manifest.json` keeps the SHA-256 of what was uploaded, so an edit of the same size within the same second is uploaded too. Two projects with the same folder name get separate copies (`<folder>-<hash>`). `MC_REMOTE_JAVA` / `MC_REMOTE_SETCCSID` override the remote `java` and `setccsid` paths. The SSH connection sends a keep-alive every 15 s and counts as lost after 60 s without any answer from the IBM i (`MC_SSH_ALIVE_SECONDS`, at least 30, gives a host that goes quiet while it compiles longer; a real drop is then noticed that much later). A lost connection is noticed by the next tool call, which connects again; `status` does not reconnect, and while disconnected it says what the reconnect costs (`reconnectCost`). A long build is best followed with a short `wait` and `build_status` calls. `status.signOns` counts the sign-ons to the build system's host over the server's life (not the PC's own read-only connections to other systems or 5250 sessions: those are under `connections`): `ssh` (start, reconnects, a `clean_remote` that had to sign on again), `ibmiEarlierSessions` (the connections MC opened on the IBM i in sessions that ended: each session starts a new MC there, which keeps its count in `~/mc/.mc/<token>.opens`), `ibmiThisSession` and `total` (`complete: false` when a count is not known yet). `clean_remote` stops MC and deletes the files over the session's own SSH connection, and refuses while a call is still running there.
* Library list `{--libl "<libs>"}` / `{--curlib <lib>}`: `CHGLIBL` / `CHGCURLIB` for the build job (instead of the Code for IBM i connection's).
* Build over SSH `{--remote --project <dir>}` (alias `--ssh-build`): the build from the command line without the host servers. MC uploads itself and the project as above, runs the build on the IBM i as the SSH user's job (`java -jar MC.jar <your options> --project <remote copy>`) and streams its output and exit code back. Takes `--project` (and `--spec`) and the build options (`-x`, `-v`, `-c`, `-k`, `--dry-run`, `--diff`, `--no-migrate`, `--lib`, `--libl`, `--curlib`, `--json <file>`, `--clean-remote`: deletes the copy and MC's jar after a successful build, but keeps either while an MC server, e.g. an MCP server with `--mcp --ssh`, still runs from it; that server has the `clean_remote` tool); not `-f`, `--scan`, `-o`, `--push` or `--since` (the IBM i copy has no git history). `--code4i`, `--connection`, `--ssh-port`, `--password-stdin`, `--password-command`, `--strict-host-key` apply on the PC and are not sent. A `build.yaml` written on the PC names the PC's folder in `CHGCURDIR` (the scan puts it there): MC on the IBM i points every `CHGCURDIR` hook that names the local project folder, or one under it, at the uploaded copy (`--local-project`, set by `--remote`). The build report (`--json`) is written on the IBM i and comes back: `--json <file>` keeps it, and the end of the output is a short summary block (result, and for each failed target what failed, its command and its first messages, largest severity first; informational messages are not errors). `--clean-remote`: after a *successful* build, delete the uploaded project copy and MC's jar from the IBM i (never a `--push` folder, and only a copy MC marked as its own); after a failed build they stay for inspection. The next `--remote` uploads everything again.
* Connection doctor `{--doctor}` (alias `--explain-connection`): prints where the connection comes from (`--code4i`, `.env`, environment variables), host, user, SSH port and where it came from, how the password is supplied (never the password), current library, library list and home directory and Java's CA store (`TLS CA store`: without one, 5250 sessions over TLS fail), then tests TCP, SSH sign-on (`$HOME`, `pwd`, Java version) and the host servers (sign-on, `CURRENT SERVER`, library list) and says which transport is usable. Every sign-on goes through the sign-on guard: a password SSH rejects is not sent to the host servers. Exit code 0 when SSH or the host servers work; when only the host servers do, a `SSH NOT USABLE` line says `--remote` and `--mcp --ssh` cannot run now. An SSH server that drops new connections before the sign-on (`Exceeded MaxStartups`, `closed by foreign host`; PUB400 when busy) is tried 4 times, 2, 5 and 10 s apart, everywhere MC connects over SSH.
* Passwords: `--password-stdin` (first line of stdin; not with `--mcp`), `IBMI_PASSWORD_FILE` (first line of a file only its owner can read: `chmod 600`), `--password-command` / `IBMI_PASSWORD_COMMAND` (a command run through the shell, once; its first output line is the password: `secret-tool lookup service myibmi`, `pass show ibmi`, `op read ...`; it has 60 s, its output is never logged and its stderr goes to the terminal), `IBMI_PASSWORD` (the environment, or VS Code's password prompt), `SSH_ASKPASS` (called with the prompt as its argument, when ssh itself would use it: always with `SSH_ASKPASS_REQUIRE=force` or `prefer`, never with `never`, otherwise only without a terminal and with a display), and a prompt on the terminal when none is set. In that order. The command-line builds sign on through the same guard as the MCP server: a rejected password is one attempt, remembered in `.mc/auth-blocked.json`.
* Install skills `{--install-skills [--agent copilot|claude|agents|all]}`: copy MC's agent skills into the personal skills folder of each agent named, comma-separated: `copilot` (`~/.copilot/skills`, the default), `claude` (`~/.claude/skills`), `agents` (`~/.agents/skills`, read by Codex, Grok Build and OpenCode) or `all`. `~` is Java's `user.home`, which can differ from `$HOME` (a sandbox, a container, another user's shell): `{--install-skills --skills-dir <dir>}` installs into that one skills folder instead (it gets `mastercompiler/`, ...; not with `--agent`). See [Agents.md](./Agents.md).
* VS Code setup `{--setup-vscode}` (with `--project`, `--connection`): write `.vscode/mcp.json` (MC as the `mastercompiler` MCP server for Copilot, VS Code prompts for the password) and install MC's agent skills into `.github/skills/`. The server reaches the IBM i over SSH (`--ssh`, like Code for IBM i); `--host-servers` uses the host servers instead (like ACS). `--print` previews without writing. See [Copilot.md](./Copilot.md).
* TOBi / Bob project `{--from-tobi <project-root>}`: use a TOBi (Better Object Builder) project as it is. Every `Rules.mk` under the root becomes an MC target (`FAM300.MODULE: FAM300.RPGLE` is a module, `ARTICLE.FILE: ARTICLE.PF` a DDS physical file, `POPEMP.PGM: popemp.sqlprc` an SQL procedure), with its `private` parameters, `CMD_PGM` and declared dependencies; `iproj.json` `includePath` becomes `INCDIR`. Description files (`.ILESRVPGM`, `.BNDDIR`, `.DTAARA`, `.DTAQ`, `.MSGF`) become the create command's params plus `ADDBNDDIRE` / `ADDMSGD` hooks. What MC cannot build (C, C++, COBOL, `CRTPGM` from modules, panel groups, menus, system triggers, make recipes) is logged as skipped. Builds directly, or with `--generate-only -o build.yaml` writes the converted spec. Works with `--mcp`.
* Download objects `{--download LIB/OBJ[,LIB/OBJ...] -o <dir>}`: over SSH, each object (its `*MODULE`, `*PGM` and `*SRVPGM`) is saved with `SAVOBJ` into a temporary save file (in `--curlib`, else the Code for IBM i current library, else `QGPL`; created and deleted by MC) and downloaded as `<dir>/<LIB>_<OBJ>.savf` (default folder `.`): the raw object as the IBM i stores it, for offline analysis or a CI artifact; `RSTOBJ` restores it. Taking the object item out of the save file is not done by MC. Takes `--code4i`, `--connection`, `--ssh-port`, `--strict-host-key`, `--password-stdin`, `--password-command`, `--curlib`, `-v`.
* Build only some targets `{--target NAME[,NAME.TYPE...]}`: the named targets and nothing else (no dependents, no dependencies; the global `before:` / `after:` hooks still run, the rest of the spec is reported `skipped`). `NAME` takes every target of that object name in one library (a module and its service program share one), `NAME.TYPE` the one of that object type (`DUMPSTATIC.SRVPGM`). The spec's own form works too, as much of it as you need: `LIB/NAME`, `LIB.NAME.TYPE`, `LIB.NAME.TYPE.SOURCETYPE` (`ROBKRAUDY2.ARTPSETL.PGM.RPGLE`). A name that exists in several libraries stops with the qualified names to choose from. A name the spec does not have stops before anything runs and lists the targets there are. For loops of edit, build one target, run it; not with `--diff`, `--since`, `--mcp` or `--generate-only`.
* Run a program `{--call LIB/PGM [--call-params '<json>'] [--libl "<libs>"] [--curlib <lib>]}`: `CHGLIBL` and the call happen in the same job, so the library list you gave is the one the program runs with (separate `system` calls over SSH each get their own job). Prints the program's parameters as it left them, its messages and, last, every spooled file its job wrote: `QPRINT` (what a C program prints with `printf`; a CL program's `DSPLIBL`), and its job log when it has one. MC ends the call's job first so those files are closed and complete, then waits (up to 15 s) until the job's own files are no longer `OPEN` / `CLOSED` and have lines; each one is shown with its qualified job name and status. Only the job's own spooled files are ever read: when the job owns none, the answer says so and lists (names only, not read) the user's files made since the call began. A spooled `QPRINT` line is 132 characters wide: a longer `printf` line may be cut and the output after it lost, so keep lines shorter or write the probe's answer to a stream file (`--fetch`) or a physical file (`--sql`); the program's own job log is only written when the job log level asks for it. `--call-params` takes `call_program`'s parameters: `[{"type":"char","length":10,"value":"A"},{"type":"packed","digits":7,"decimals":2},{"type":"int","bytes":4}]` (no value: blanks / zero are passed in; every parameter is input and output). A `varchar` parameter, `{"type":"varchar","length":2000,"value":"{\"a\":1}"}`, is an RPG `VARCHAR(2000)`: a 2-byte length followed by the text (`"prefix":4` for `VARCHAR(2000:4)`), e.g. a JSON text. `length` is required: the payload capacity the program declares, in bytes, using the connection's resolved CCSID as `char` does (1 to 65535 with the 2-byte prefix; the 4-byte prefix allows more). It comes back as the text the program left in it, trailing blanks included. `char` stays the fixed-length buffer. Without `--libl` / `--curlib` the Code for IBM i connection's lists, else MC's own job's; `--libl` alone leaves the current library as the job has it. Up to 2000 lines of each spooled file; the call has 120 s. `--json <file>` writes everything above as JSON; exit code `0` when the program ended without an error message, `1` otherwise. `--dry-run` shows what would be called. A call that does not end in 120 s has its job ended, and the error says what the job was waiting on (its status, e.g. `MSGW`, and the last messages of its job log): a batch / server job waits forever for the reply to an RPG *inquiry message* (invalid numeric data), and `--inqmsgry` answers those with their default reply (`CHGJOB INQMSGRPY(*DFT)` in the call's job) so the error lands in the job log instead. There is no confirmation: it is the command you typed, run as your user (the MCP `call_program` is the one that previews and refuses protected libraries). A program that ends in error inside the call's server job (QZRCSRVS, owned by QUSER) does not have its stderr or job-log line in `--call`'s answer; the user's profile usually cannot read that job's log. A program that must report a failure should write its details to a stream file (read with `--fetch`) or to `QPRINT`. Over SSH: `--remote --project <folder> --call LIB/PGM` uploads the folder (it carries MC) and runs the call on the IBM i, no host servers needed; `--json` is not available there. Takes `--code4i`, `--connection`, `--ssh-port`, `--strict-host-key`, `--password-stdin`, `--password-command`, `-x`, `-v`.
* Import source members `{--import <selection> -o <dir>}`: turn source members into an MC repository. The selection is a comma-separated list of `LIB`, `LIB/SRCPF` or `LIB/SRCPF/MBR` (`MBR` may end with `*`); several libraries get a folder each. Each member becomes a UTF-8 stream file `<dir>/<SRCPF>/<name>`, named from the object the IBM i says was built from it (`HOLA.pgm.rpgle`, `FAM300.module.rpgle`, `ARTICLE.pf.dds`, `GET_NAME.function.sql`); members other sources `/COPY` become `NAME.include.<type>`. Writes `mc-import.json` (how each member was named, and which ones were only assumed) and a scanned `build.yaml` + `build.cl`. Reads members through SQL, so it works on the IBM i or remotely. Exits `1` when a member could not be read.
* MCP server `{--mcp}`: serve the spec to AI agents over stdio (Model Context Protocol) instead of building once. Tools: `build` and `plan` (select targets by changed `files` or a git `since` ref, plus dependents), `impact` (what depends on an object) and `joblog`. One IBM i connection stays open across calls; the spec is re-read on each call. Takes `-f`/`--scan`, `--push`, `--lib`, `--base`, `--no-migrate`, `-x`, `-v`. See the [mastercompiler skill](../skills/mastercompiler/SKILL.md#mcp-server) for client setup.
* Connections to the IBM i `{--mcp [--single-connection]}`: every new host-server connection signs on again (measured on PUB400: a JDBC connection is a sign-on server exchange plus a database server job `QZDASOINIT`; a command or IFS request starts a `QZRCSRVS` / `QPWFSERVSO` job with the same credentials), and some systems refuse sign-ons that come close together. By default the MCP server holds two JDBC connections: the build job, and one read connection for `sql`, `checkRows` and the job monitor (`whats_running`, `cancel`, hook timeouts). The read connection is opened once (JT400 access `read call`, `setReadOnly(true)`: jt400 refuses everything but SELECT and CALL; what `sql` runs is checked by MC first) and a library-list change is applied on it with `CHGLIBL` instead of a new connection. `copy_rows` with `confirm` opens a write connection for the copy. `--single-connection` runs `sql`, `checkRows`, the monitor and `copy_rows` on the build connection, so the server holds exactly one JDBC connection; that connection then opens with system naming and ISO dates and times, as the read connection does (unqualified names resolve through the library list; MC's own SQL there is qualified, and SQL sources build with RUNSQLSTM's own `NAMING`). Its guard: MC's checks as always (SELECT / VALUES / WITH only, one statement, no FINAL TABLE, no known side-effect function, Db2's parse), and each query inside a `SET TRANSACTION ISOLATION LEVEL READ COMMITTED, READ ONLY` transaction that is rolled back (Db2 refuses every data change and DDL there: SQL0817), then the connection's own commit settings come back. Not covered by that guard: what is not SQL data, e.g. a CL command or program a function calls (the known ones are refused by name). Trade-offs: queries share the build job (its QTEMP, its locks, its library list), a query waits while a build or another call runs, `whats_running` and the joblog in `build_status` cannot look at the job during a build (the stage, target and command still show), and stopping a build (`cancel`, a hook timeout) opens a second connection for CANCEL_SQL / ENDJOB, counted and closed after the stop. `run_command` always runs in the build job (`QCMDEXC` on the build connection; its answer's `job` names it); a command server job (`QZRCSRVS`, one more sign-on) is started only by `call_program` / `debug_program` and by a build that migrates a source member (it reads the user's home directory). A `run_command` that hangs (a CALL, a MSGW) shows in `whats_running` (`runCommand`), and `cancel` without a `buildId` stops it as it stops a build's command (in single mode through the counted stop connection). Default behaviour is unchanged without the option. Not with `--read-only` (a read-only server has one connection already). `status` lists them in `hostConnections`: purpose (`build`, `read-only`, `monitor`, `copy`, `command`, `files`), open or not, job, when it was opened, `opens`, `reopens`, why, which purposes share another's connection, and `totalOpens` since the server started; each open, reopen and in-place library-list change is logged at info level with its reason. The command line prints the same count at the end with `--verbose` (`IBM i connections opened: 1 (build 1)`). Read-only connections to other systems (`connections.readOnly`) are separate sign-ons by design.
* Push sources `{--push <ifs-dir>}`: upload the local git repository (all tracked and non-ignored files, or only the changed ones with `--since`) to this absolute IFS directory as UTF-8 stream files, keeping the layout, then `CHGCURDIR` to the pushed copy of the spec's directory before the spec's `before:` hooks. For remote builds from a laptop or CI.
* JSON build report `{--json <file>}`: one entry per target with `status` (`built`, `failed`, `skipped`, `planned`, `blocked`, `not_built`), the paste-ready `command` and, when a hook or test failed, the command that failed (`failedCommand`), the target's joblog messages and the compile errors read from its EVFEVENT member (`file`, `line`, `column`, `id`, `severity`, `message`; needs `OPTION(*EVENTF)`, the MC default). For CI and agents; the console log is unchanged.

## Exit codes

| Code | Meaning |
|------|---------|
| `0` | Build (or generation) succeeded |
| `1` | A target or command failed (with `--tool`, `--script`, `--batch` or a `--socket` client: a call failed) |
| `2` | Invalid arguments |
| `3` | `--socket` client: no server answers at that path, or it went away before it answered |

## Scripts and shell pipes

The MCP server speaks JSON-RPC on stdin/stdout, so a shell script can drive it without a custom harness. One server
holds one IBM i job (and over `--ssh` one SSH session) for all of a script's calls.

* `--tool <name> [--args-json '<object>' | --args-file <file|->]`, repeatable: runs the server in this process over
  those calls, one at a time, and prints one JSON line per call; then exits (`1` if a call failed). Every MCP tool works
  (`status`, `sql`, `plan`, `build`, `spool`, `joblog`, `run_command`, `upload_file`, `download_file`, `screen_*`,
  `inventory`, `clean`, ...). Implies `--batch --rpc`. Add `--ssh --project <folder>` to run MC on the IBM i over SSH.

  ```bash
  java -jar MasterCompiler.jar --tool status --tool sql --args-json '{"statement":"VALUES CURRENT USER"}'
  {"jsonrpc":"2.0","id":1,"result":{"curlib":"MYLIB", ...}}
  {"jsonrpc":"2.0","id":2,"result":{"columns":["00001"],"rows":[["ME"]],"rowCount":1}}
  ```
* `--script <file|->`: the same over a file of JSON-RPC requests (NDJSON, or pretty-printed as `jq` writes it). A
  method may name a tool, as with `--tool`: `{"id":1,"method":"run_command","params":{"command":"DSPLIBL"}}`; the
  answers stay MCP results (`content`, `structuredContent`) unless `--rpc` is given too. Elsewhere such a method gets
  `-32601 Method not found` with how to call that tool.
* `--mcp --socket <path>`: a server that stays up on a Unix domain socket (Java 16+), one client after another, keeping
  its IBM i connection between them. It prints `{"method":"notifications/ready",...}` on stdout (and on `--ready-fd N`)
  once it listens. The socket is rw for its owner only; a socket file left by a server that ended is replaced, a live one
  is refused. `{"method":"shutdown"}` (or a client's `--shutdown`) stops it, as does SIGTERM (the socket file is
  removed); a build or `run_command` still running ends first, for at most `--drain-timeout` seconds.
* `--socket <path>` without `--mcp`: the client. It sends `--tool` calls, a `--script`, or the JSON-RPC lines on stdin
  to that server, one at a time, and prints each answer; it waits up to 30 s for the server's socket to appear.
  `--wait-ready` first sends `status` with `wait: true`: over SSH it answers once MC runs on the IBM i (`5/5 ready`),
  so no polling is needed; a start-up that failed is a failed call (exit code `1`) and nothing after it is sent.
  `--shutdown` ends the server after the other requests. With either, stdin is not read (use `--script -` for that).
* Which options go where: the server's start takes `--ssh`, `--project`, `--read-only`, `-f`, `--exit-on-eof`,
  `--ready-fd` and the connection options; a client takes `--tool`, `--args-json`, `--args-file`, `--script`,
  `--wait-ready`, `--shutdown`. `--rpc`, `--batch`, `--fail-fast`, `--confirm`, `--drain-timeout` and `-v` go on
  either: on the server they are every client's default (`--fail-fast` there only with `--batch`; on a client call
  it needs nothing else). `--help` ends with this list.

  ```bash
  java -jar MasterCompiler.jar --mcp --ssh --socket /tmp/mc.sock --project . --confirm &
  java -jar MasterCompiler.jar --socket /tmp/mc.sock --wait-ready
  java -jar MasterCompiler.jar --socket /tmp/mc.sock --tool plan
  java -jar MasterCompiler.jar --socket /tmp/mc.sock --tool build --args-json '{"files":["qrpglesrc/hello.pgm.rpgle"]}'
  java -jar MasterCompiler.jar --socket /tmp/mc.sock --shutdown
  ```

What the server does for scripts:

* Input: one JSON-RPC message per line, a `Content-Length:` header block, or JSON over several lines (when its first line
  ends open: `{`, `[`, `,` or `:`). Output: always one compact line per message. Every request gets exactly one answer:
  text that is not JSON gets `-32700`, a message that is not JSON-RPC `-32600`, an unknown method `-32601` (also over
  `--ssh`). Ids are kept as sent (`1` and `"1"` differ); an id still waiting for its answer is refused.
* Tool results carry `structuredContent` (the JSON object of their text, so it is parsed once; clients on MCP
  `2025-06-18` or later, or without `initialize`) and, when they failed, `_meta.errorKind`: `NO_PROJECT`, `NO_CREDENTIALS`,
  `SIGN_ON_LIMIT`, `AUTH_BLOCKED`, `SSH_LOST`, `CONNECTION_FAILED`, `BUILD_BUSY`, `PROTECTED_LIB`, `INQMSGRPY_WAIT`,
  `SQL_ERROR`, `UNKNOWN_TOOL`, `REFUSED`, `NOT_FOUND`, `INVALID_ARGS`, `TOOL_ERROR`; a failed build `COMPILE_FAILED`,
  `HOOK_FAILED`, `TESTS_FAILED`, `TIMEOUT`, `CANCELLED` or `BUILD_FAILED`; another JSON result `TOOL_FAILED`.
* `--rpc`: a tool's result is its JSON itself (`{"text": ...}` when it answered text), a failed call a JSON-RPC error
  `-32000` with `data.errorKind` (and `data.result`); its `message` is the errorKind and, when the result says why
  (`error`, e.g. a failed `run_command`'s escape message and diagnostics), that line: `TOOL_FAILED: CPF3C50 Program X
  not created. Diagnostics: CPD5D02 ...`. A method may name a tool: `{"id":1,"method":"status"}` (a method with a `/`
  is MCP's, never a tool).
  `initialize` is optional.
* When the input ends (`printf ... | mc --mcp`), `--exit-on-eof drain` (the default) answers every request already read,
  and over stdio waits for a build or `run_command` still running, before it closes the IBM i connection; for at most
  `--drain-timeout` seconds (900), then whatever is left is answered with `DRAIN_TIMEOUT` (`-32001`: the result is
  unknown). `--exit-on-eof abort` exits at once.
* `--batch`: no dialog, console prompt, `SSH_ASKPASS` or URL elicitation (a 5250 secret is refused with why); the next
  request is read once the last one was answered, so the answers come in order; a `build` that answers `running` is
  followed with `build_status` under the same id until it ends; exit code `1` when a call failed. `--fail-fast` sends no
  request after the first that failed; `--confirm` sets `confirm: true` on calls that do not set `confirm` (no preview
  round trip; `confirm: false` stays).

`-` for stdin and stdout: `--json -` writes the JSON (build report, `--sql` rows, `--call` result) to stdout and moves
logs and the text table to stderr; `--fetch <path> -o -` writes the file's bytes to stdout; `--sql -`, `--call-params -`,
`--args-file -` and `--script -` read stdin (only one of them, and not with `--password-stdin`).

## Params permutation

Simplest call, just the file path
```bash
java -jar MasterCompiler.jar -f /home/user/mylib.hello.pgm.rpgle.yaml
# Same as above
java -jar MasterCompiler.jar --file /home/user/mylib.hello.pgm.rpgle.yaml
```

Add debug flag
```bash
java -jar MasterCompiler.jar -f /home/user/mylib.hello.pgm.rpgle.yaml -x
# Same as above
java -jar MasterCompiler.jar -x -f /home/user/mylib.hello.pgm.rpgle.yaml
```

Add verbose flag
```bash
java -jar MasterCompiler.jar -f /home/user/mylib.hello.pgm.rpgle.yaml -v
```

Add debug verbose flag
```bash
java -jar MasterCompiler.jar -f /home/user/mylib.hello.pgm.rpgle.yaml -xv
```

Add dry run
```bash
java -jar MasterCompiler.jar -f /home/user/mylib.hello.pgm.rpgle.yaml --dry-run
```

Add diff build
```bash
java -jar MasterCompiler.jar -f /home/user/mylib.hello.pgm.rpgle.yaml --diff
```

Add no migrate
```bash
java -jar MasterCompiler.jar -f /home/user/mylib.hello.pgm.rpgle.yaml --no-migrate
```

## Scan mode (auto YAML)

Generate a topo-sorted spec from a source tree without writing targets by hand:

```bash
java -jar MasterCompiler.jar --scan /home/user/sources --generate-only -o build.yaml -v
```

Rewrite an existing spec (refresh paste-ready `#` comments after you edit `params:`):

```bash
java -jar MasterCompiler.jar -f build.yaml --generate-only -o build.yaml
```

Scan and compile in one step:

```bash
java -jar MasterCompiler.jar --scan /home/user/sources --lib curlib -xv
```

Optional explicit base overlay (otherwise uses `<scan>/mc-base.yaml` when present):

```bash
java -jar MasterCompiler.jar \
  --scan /home/user/sources --base /home/user/sources/mc-base.yaml -o build.yaml -xv
```

[Scan doc](./Scan.md)
* Read a table `{--sql "SELECT ..."}`: the MCP `sql` tool from the command line. One `SELECT` / `VALUES` / `WITH` on a read-only connection (nothing can be changed), rows printed tab-separated with a header and the row count; `--max-rows N` (default 100), `--libl "<libs>"` / `--curlib <lib>` for the connection's library list (else Code for IBM i's), `--json <file>` writes the rows as JSON. For a probe that writes its answer to a physical file instead of the spool.
* A sign-on budget `{--mcp --max-sign-ons N}`: at most N sign-ons to the build system over the server's life: SSH, every connection MC opens there (build, read, monitor, copy, the command and file services), 5250 sessions and the password check before a 5250 sign-on with a code. Each is taken before it signs on; with none left the call is refused and nothing signs on (`status` → `signOnBudget`). Over SSH the server on the PC keeps the one count: MC on the IBM i asks it before each connection, also across reconnects. Read-only connections to other systems are not in it.
* No overwrite `{--mcp --no-overwrite}`: `build`'s `noOverwrite` is on by default (a build that would replace an object this project did not build is refused before anything runs; `plan` lists them in `wouldReplace`), and over SSH with `--push` the sync refuses to replace or delete a file there that this project did not upload (`.mc/sync-manifest.json`).
* Check before the upload `{--mcp --ssh --no-initial-sync}`: MC starts on the IBM i with only the spec files uploaded; the sources go up with the first call that reads them (`open_project` `sync: false` does the same for one project).
* Copy an IFS file `{--fetch /path/file [-o <file or folder>]}` (over MCP: the `download_file` tool, which over SSH needs no sign-on of its own): the stream file byte for byte to this PC (default: its name in the current folder), through the file host server. For a probe that writes its answer to the IFS. The `Fetched ...` line gives the size, the source's last modification time and its CCSID, so a stale file is visible. Repeat `--fetch` with `-o <folder>` to copy several files in one run.
