---
name: ibm-i-pase
description: >-
  Work on an IBM i over SSH through PASE: run CL with the `system` utility, SQL
  through QShell `db2`, move between the IFS and QSYS.LIB, read source and
  EVFEVENT members, and keep one authenticated SSH connection for many
  non-interactive commands. Use when an agent must inspect or change an IBM i
  from a shell. Triggers: IBM i, AS/400, iSeries, PASE, QShell, qsh, QSYS,
  QOpenSys, IFS, CL, DSPLIBL, DSPOBJD, CPYTOSTMF, EVFEVENT, CCSID, EBCDIC.
---

# IBM i through PASE (SSH)

SSH into an IBM i lands in **PASE**, an AIX-compatible Unix environment. Native
objects (libraries, programs, files, jobs) are reached from there with bridges:
`system` for CL, `qsh -c 'db2 ...'` for SQL, and `/QSYS.LIB/...` paths for objects.
Agents should use this path, not a 5250 green screen.

| Layer | What it is | Agent access |
|-------|------------|--------------|
| PASE | Unix runtime (`/QOpenSys`, bash, `ls`, `java`, `git` when installed) | SSH login shell |
| IFS | Stream file tree (`/home`, `/QOpenSys`, `/QSYS.LIB`) | Normal Unix paths |
| QSYS | Libraries and objects (`*PGM`, `*FILE`, ...) | CL through `system`, or `/QSYS.LIB/LIB.LIB/OBJ.TYPE` |
| QShell | IBM i utilities such as `db2` | `qsh -c '...'` |

## Connect

Many systems allow only password (keyboard-interactive) login. Agents cannot type
passwords, so let the operator open one **SSH ControlMaster** connection, then run
every command through its socket without prompts:

```bash
# operator, once, in their own terminal:
ssh -M -S /tmp/ibmi.sock -o ControlPersist=4h -fN USER@HOST
# agent, as often as needed:
ssh -S /tmp/ibmi.sock USER@HOST 'system -i "DSPLIBL"'
scp -o ControlPath=/tmp/ibmi.sock file USER@HOST:dir/
ssh -S /tmp/ibmi.sock -O check USER@HOST      # is the master alive?
```

- If the operator sees `ControlSocket ... already exists, disabling multiplexing`, a
  master is already running; check it with `-O check` instead of opening another.
- Never write the password to a file, a command line or a log.
- Some hosts print a login banner on every connection; filter it from captured
  output.

## Orient first (read-only)

```bash
uname -a            # "OS400 <host> 5 7 ..." = IBM i 7.5 (release 5, version 7)
echo $HOME          # IBM i uppercases profile names: /home/USERNAME
java -version; command -v git
system -i "DSPLIBL"                  # library list; CUR = current library
system -i "DSPOBJD OBJ(MYLIB/*ALL) OBJTYPE(*ALL)" | head -40
ls /QSYS.LIB/MYLIB.LIB | head        # objects as IFS entries (NAME.PGM, NAME.FILE, ...)
```

## CL from PASE

```bash
system -i "CALL MYLIB/MYPGM PARM('ARG')"   # -i: run in this same job
```

- Quote the whole CL string so the shell leaves `()`, `*` and `$` alone.
- Messages such as `CPD000D ... not safe for a multithreaded job` are noise on many
  commands; the command still runs. Real failures show as `CPF`/`CPD` escape messages.
- Prefer `DSP*` commands; interactive `WRK*` commands expect a 5250 screen.
- If output is empty or garbled, try `system -O` (CCSID conversion) or
  `export QIBM_USE_DESCRIPTOR_STDIO=Y`.

## SQL from PASE

```bash
qsh -c 'db2 "select count(*) from qsys2.systables where table_schema = '\''MYLIB'\''"'
```

- Nested quoting gets fragile fast. Put the SQL in a small script file, copy it over,
  and run `bash script.sh`.
- Each `db2` call is its own connection, so `QTEMP` (aliases, temp tables) does not
  survive from one call to the next.
- Catalog views live in `QSYS2` (`SYSTABLES`, `SYSCOLUMNS`, `SYSPARTITIONSTAT`,
  `OBJECT_STATISTICS`, `JOBLOG_INFO`, `LIBRARY_LIST_INFO`).

## Encodings (CCSID)

- The system and jobs are usually EBCDIC (for example CCSID 37 or 273); PASE is
  usually UTF-8 (CCSID 1208).
- Source members are EBCDIC records; do not `cat` them through `/QSYS.LIB`. Copy them
  to a stream file first:
  ```bash
  system -i "CPYTOSTMF FROMMBR('/QSYS.LIB/MYLIB.LIB/QRPGLESRC.FILE/HELLO.MBR') TOSTMF('/home/USER/hello.rpgle') STMFOPT(*REPLACE) STMFCCSID(1208)"
  ```

## EVFEVENT (compiler event files)

A compile with `OPTION(*EVENTF)` writes `LIB/EVFEVENT(OBJECT)`: one member per
compiled object, in the object's library.

- Records: `TIMESTAMP 0 yyyyMMddHHmmss`, `PROCESSOR` (one block per compiler pass,
  e.g. SQL precompiler then RPG), `FILEID` (id → source path), and
  `ERROR 0 <fileid> <class> <stmt> <line> <col> <endline> <endcol> <msgid> <sevchar> <sev> <len> <text>`.
- The record column is **`CHAR(400) FOR BIT DATA`**; over JDBC/ODBC it comes back
  as hex. Cast it to read text:
  `SELECT CAST(EVFEVENT AS VARCHAR(400) CCSID 37) FROM <alias over the member>`.
- Reading a member through SQL needs an alias:
  `CREATE OR REPLACE ALIAS QTEMP.EVF FOR MYLIB.EVFEVENT (OBJECT)`.
- The SQL catalog (`SYSPARTITIONSTAT`, `SYSCOLUMNS`) may not list EVFEVENT; use the
  member's own `TIMESTAMP` record or `ls /QSYS.LIB/MYLIB.LIB/EVFEVENT.FILE` instead.
- Quick look from PASE: `CPYTOSTMF` the member (as above) and `grep '^ERROR'`.

## Safety and etiquette

- Many IBM i systems are shared (including public ones such as PUB400.COM). Access is
  logged. Keep listings bounded (`| head`), avoid heavy scans, and never touch other
  users' objects.
- Read-only by default. State what will change before any `CRT*`, `DLT*`, `CHG*`
  or data change, and only run it when the task calls for it.
- Build and test in your own or a scratch library, never a production library.
