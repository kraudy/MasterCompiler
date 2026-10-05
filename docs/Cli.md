# Cli

Master compiler follows unix philosophi in various parts of its design. One of them is the CLI param validation.

* All params are validated at the start, so you don't have to insist later on interactive promopts. 
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
* Dry run execution allows to run the compiler without executing any commands, it follows the flow of exceution and generates the command's strings. `{--dry-run}`
* No migrate flag ommits souce files migration `{--no-migrate}`
* Incremental build `{--diff}`: compile targets whose source is newer than the object (or whose object is missing), plus every dependent. Source time is the newest of the stream file and any `/copy`/`/include` attachments (`File` / `IFSFile`). Does not `touch` sources.
* Clean deletes created objects after the build `{ -c, --clean }`
* Keep going `{-k, --keep-going}`: after a failed target, skip only its dependents (`blocked` in the report) and build everything else, so one run reports every broken target. Exit code is still `1` when anything failed. MCP `build` keeps going by default.
* Git incremental build `{--since <git-ref>}`: compile targets whose source or any `/copy`/`/include` attachment changed since the ref (committed, uncommitted and untracked files), plus every dependent. No timestamps, so fresh clones and branch switches only rebuild what changed. Cannot be combined with `--diff`.
* Project `{--project <dir>}`: use what the project has — `build.yaml` if present, else TOBi `Rules.mk` (as `--from-tobi`), else a scan.
* Code for IBM i connection `{--code4i}` / `{--connection <name>}`: connect with the connection configured in Code for IBM i (VS Code user settings: host, user, current library, library list, home directory); the password comes from `IBMI_PASSWORD`. The build job gets that library list, and remote builds push to `<home>/mc/<project folder>` unless `--push` is given. `--connection` picks one of several connections.
* SSH `{--mcp --ssh --project <dir>}`: reach the IBM i over SSH, the way Code for IBM i does (port 22 instead of the host servers, which firewalls often block). On the PC, MC uploads its own jar once per version to `<home>/mc/.mc/`, syncs the project to `<home>/mc/<folder>` (only files whose size or time changed, tagged CCSID 1208; deleted files are removed), starts `java -jar MC.jar --mcp` on the IBM i, where it builds as the SSH user's job, and relays MCP. Before `build` / `plan` / `impact` it uploads what changed; a git `since` is turned into a file list. Login: the connection's or `~/.ssh` key (`id_ed25519`, `id_ecdsa`, `id_rsa`), else `IBMI_PASSWORD` (password or keyboard-interactive). Host keys: unknown hosts are trusted and added to `~/.ssh/known_hosts`, changed keys are refused. With `--code4i` the host, port, user, home directory and library list come from Code for IBM i; otherwise `IBMI_HOSTNAME`, `IBMI_USERNAME`, `IBMI_SSH_PORT` (default 22). `MC_REMOTE_JAVA` / `MC_REMOTE_SETCCSID` override the remote `java` and `setccsid` paths.
* Library list `{--libl "<libs>"}` / `{--curlib <lib>}`: `CHGLIBL` / `CHGCURLIB` for the build job (instead of the Code for IBM i connection's).
* VS Code setup `{--setup-vscode}` (with `--project`, `--connection`): write `.vscode/mcp.json` (MC as the `mastercompiler` MCP server for Copilot, VS Code prompts for the password) and install MC's agent skills into `.github/skills/`. The server reaches the IBM i over SSH (`--ssh`, like Code for IBM i); `--host-servers` uses the host servers instead (like ACS). `--print` previews without writing. See [Copilot.md](./Copilot.md).
* TOBi / Bob project `{--from-tobi <project-root>}`: use a TOBi (Better Object Builder) project as it is. Every `Rules.mk` under the root becomes an MC target (`FAM300.MODULE: FAM300.RPGLE` is a module, `ARTICLE.FILE: ARTICLE.PF` a DDS physical file, `POPEMP.PGM: popemp.sqlprc` an SQL procedure), with its `private` parameters, `CMD_PGM` and declared dependencies; `iproj.json` `includePath` becomes `INCDIR`. Description files (`.ILESRVPGM`, `.BNDDIR`, `.DTAARA`, `.DTAQ`, `.MSGF`) become the create command's params plus `ADDBNDDIRE` / `ADDMSGD` hooks. What MC cannot build (C, C++, COBOL, `CRTPGM` from modules, panel groups, menus, system triggers, make recipes) is logged as skipped. Builds directly, or with `--generate-only -o build.yaml` writes the converted spec. Works with `--mcp`.
* Import source members `{--import <selection> -o <dir>}`: turn source members into an MC repository. The selection is a comma-separated list of `LIB`, `LIB/SRCPF` or `LIB/SRCPF/MBR` (`MBR` may end with `*`); several libraries get a folder each. Each member becomes a UTF-8 stream file `<dir>/<SRCPF>/<name>`, named from the object the IBM i says was built from it (`HOLA.pgm.rpgle`, `FAM300.module.rpgle`, `ARTICLE.pf.dds`, `GET_NAME.function.sql`); members other sources `/COPY` become `NAME.include.<type>`. Writes `mc-import.json` (how each member was named, and which ones were only assumed) and a scanned `build.yaml` + `build.cl`. Reads members through SQL, so it works on the IBM i or remotely. Exits `1` when a member could not be read.
* MCP server `{--mcp}`: serve the spec to AI agents over stdio (Model Context Protocol) instead of building once. Tools: `build` and `plan` (select targets by changed `files` or a git `since` ref, plus dependents), `impact` (what depends on an object) and `joblog`. One IBM i connection stays open across calls; the spec is re-read on each call. Takes `-f`/`--scan`, `--push`, `--lib`, `--base`, `--no-migrate`, `-x`, `-v`. See the [mastercompiler skill](../skills/mastercompiler/SKILL.md#mcp-server) for client setup.
* Push sources `{--push <ifs-dir>}`: upload the local git repository (all tracked and non-ignored files, or only the changed ones with `--since`) to this absolute IFS directory as UTF-8 stream files, keeping the layout, then `CHGCURDIR` to the pushed copy of the spec's directory before the spec's `before:` hooks. For remote builds from a laptop or CI.
* JSON build report `{--json <file>}`: one entry per target with `status` (`built`, `failed`, `skipped`, `planned`, `blocked`, `not_built`), the paste-ready `command`, the target's joblog messages and the compile errors read from its EVFEVENT member (`file`, `line`, `column`, `id`, `severity`, `message`; needs `OPTION(*EVENTF)`, the MC default). For CI and agents; the console log is unchanged.

## Exit codes

| Code | Meaning |
|------|---------|
| `0` | Build (or generation) succeeded |
| `1` | A target or command failed |
| `2` | Invalid arguments |

## Params permutation

Simplest call, just the file path
```bash
java -jar MasterCompiler-1.0-SNAPSHOT.jar -f /home/user/mylib.hello.pgm.rpgle.yaml
# Same as above
java -jar MasterCompiler-1.0-SNAPSHOT.jar --file /home/user/mylib.hello.pgm.rpgle.yaml
```

Add debug flag
```bash
java -jar MasterCompiler-1.0-SNAPSHOT.jar -f /home/user/mylib.hello.pgm.rpgle.yaml -x
# Same as above
java -jar MasterCompiler-1.0-SNAPSHOT.jar -x -f /home/user/mylib.hello.pgm.rpgle.yaml
```

Add verbose flag
```bash
java -jar MasterCompiler-1.0-SNAPSHOT.jar -f /home/user/mylib.hello.pgm.rpgle.yaml -v
```

Add debug verbose flag
```bash
java -jar MasterCompiler-1.0-SNAPSHOT.jar -f /home/user/mylib.hello.pgm.rpgle.yaml -xv
```

Add dry run
```bash
java -jar MasterCompiler-1.0-SNAPSHOT.jar -f /home/user/mylib.hello.pgm.rpgle.yaml --dry-run
```

Add diff build
```bash
java -jar MasterCompiler-1.0-SNAPSHOT.jar -f /home/user/mylib.hello.pgm.rpgle.yaml --diff
```

Add no migrate
```bash
java -jar MasterCompiler-1.0-SNAPSHOT.jar -f /home/user/mylib.hello.pgm.rpgle.yaml --no-migrate
```

## Scan mode (auto YAML)

Generate a topo-sorted spec from a source tree without writing targets by hand:

```bash
java -jar MasterCompiler-1.0-SNAPSHOT.jar --scan /home/user/sources --generate-only -o build.yaml -v
```

Rewrite an existing spec (refresh paste-ready `#` comments after you edit `params:`):

```bash
java -jar MasterCompiler-1.0-SNAPSHOT.jar -f build.yaml --generate-only -o build.yaml
```

Scan and compile in one step:

```bash
java -jar MasterCompiler-1.0-SNAPSHOT.jar --scan /home/user/sources --lib curlib -xv
```

Optional explicit base overlay (otherwise uses `<scan>/mc-base.yaml` when present):

```bash
java -jar MasterCompiler-1.0-SNAPSHOT.jar \
  --scan /home/user/sources --base /home/user/sources/mc-base.yaml -o build.yaml -xv
```

[Scan doc](./Scan.md)