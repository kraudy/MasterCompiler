# Spec

To define the flow of compilation, we use a yaml based spec. Here, you have the flexibility to define global or specific per [compilation target](./TargetKey.md) hooks to be executed before, after, or in case of success or failure during, before, or after compiling any target. This affords a lot of flexibility in a clean manner.

Many targets can be defined inside a spec and set the compilation environment for each one of them and also for the whole flow. All in the same file and readable in one swoop.

Every command and param is validated at deserialization, so if you have wrongly typed params or the wrong param for a command, MC will instantly tell you, following the spirit of fail loud and early. This also helps to keep the code clean since no more syntactic validation is needed and everything is already mapped to Java objects (which are also data structures... heap allocation).

- [Simplest spec](#simplest-spec)
- [ILE spec](#ile-spec)
- [Error spec](#error-spec)

## Simplest spec

A single compilation target without compilation params or any pre or post compilation hook. MC will try to extract compilation information from the object. If not found, sensible defaults will be used.

```yaml
targets:
  "robkraudy1.hello.pgm.rpgle": {}
```

Save the yaml file and call the compiler. Note how we are using `{-xv | -x: debug, -v: verbose}` flags to get the full output. And `{-f, --file: Spec file}` for the spec path.
```
java -jar MasterCompiler-1.0-SNAPSHOT.jar -xv -f /home/ROBKRAUDY/yaml/robkraudy1.hello.pgm.rpgle.yaml
```

Full log
```diff
+ 05:40:33.424 [main] INFO  c.g.kraudy.compiler.MasterCompiler - 
Library list: 
ROBKRAUDY1
QTEMP

+ 05:40:33.431 [main] INFO  c.g.kraudy.compiler.MasterCompiler - Building: ROBKRAUDY1.HELLO.PGM.RPGLE
+ 05:40:34.403 [main] INFO  c.g.kraudy.compiler.ObjectDescriptor - Found pgm object compilation info 'ROBKRAUDY1.HELLO.PGM.RPGLE
+ 05:40:34.408 [main] INFO  com.github.kraudy.compiler.Migrator - Migrating source member to stream file
+ 05:40:34.426 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: CPYTOSTMF FROMMBR(''/QSYS.lib/ROBKRAUDY1.lib/QRPGLESRC.file/HELLO.mbr'') TOSTMF(''/home/ROBKRAUDY/sources/ROBKRAUDY1.HELLO.PGM.RPGLE'') STMFOPT(*REPLACE) STMFCCSID(1208) ENDLINFMT(*LF)
+ 05:40:34.443 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Joblog info
2025-12-28 05:40:34  | CPCA082    | 0    | Object copied.

+ 05:40:34.446 [main] INFO  com.github.kraudy.compiler.ParamMap - 
Command CRTBNDRPG summary
PGM:[[INIT], ROBKRAUDY1/HELLO, *CURLIB/HELLO]
SRCFILE:[[INIT], ROBKRAUDY1/QRPGLESRC, [REMOVED]]
SRCMBR:[[INIT], HELLO, [REMOVED]]
SRCSTMF:[[INIT], ''/home/ROBKRAUDY/sources/ROBKRAUDY1.HELLO.PGM.RPGLE'']
DFTACTGRP:[[INIT], *NO]
ACTGRP:[[INIT], QILE]
STGMDL:[[INIT], *SNGLVL]
OPTION:[[INIT], *EVENTF]
DBGVIEW:[[INIT], *ALL]
REPLACE:[[INIT], *YES]
USRPRF:[[INIT], *USER]
TGTRLS:[[INIT], *CURRENT, V7R5M0]
PRFDTA:[[INIT], *NOCOL]
TGTCCSID:[[INIT], *JOB]

+ 05:40:34.866 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: CRTBNDRPG PGM(*CURLIB/HELLO) SRCSTMF(''/home/ROBKRAUDY/sources/ROBKRAUDY1.HELLO.PGM.RPGLE'') DFTACTGRP(*NO) ACTGRP(QILE) STGMDL(*SNGLVL) OPTION(*EVENTF) DBGVIEW(*ALL) REPLACE(*YES) USRPRF(*USER) TGTRLS(V7R5M0) PRFDTA(*NOCOL) TGTCCSID(*JOB)
05:40:34.881 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Joblog info
2025-12-28 05:40:34  | CPI2119    | 0    | AUT and USRPRF parameter values were ignored.
2025-12-28 05:40:34  | CPI2121    | 0    | Replaced object HELLO type *PGM was moved to QRPLOBJ.
2025-12-28 05:40:34  | RNS9304    | 0    | Program HELLO placed in library ROBKRAUDY1. 00 highest severity. Created on 25-12-28 at 05:40:34.

+ 05:40:34.881 [main] INFO  c.g.kraudy.compiler.MasterCompiler - 
Chain of commands: CPYTOSTMF FROMMBR(''/QSYS.lib/ROBKRAUDY1.lib/QRPGLESRC.file/HELLO.mbr'') TOSTMF(''/home/ROBKRAUDY/sources/ROBKRAUDY1.HELLO.PGM.RPGLE'') STMFOPT(*REPLACE) STMFCCSID(1208) ENDLINFMT(*LF) => CRTBNDRPG PGM(*CURLIB/HELLO) SRCSTMF(''/home/ROBKRAUDY/sources/ROBKRAUDY1.HELLO.PGM.RPGLE'') DFTACTGRP(*NO) ACTGRP(QILE) STGMDL(*SNGLVL) OPTION(*EVENTF) DBGVIEW(*ALL) REPLACE(*YES) USRPRF(*USER) TGTRLS(V7R5M0) PRFDTA(*NOCOL) TGTCCSID(*JOB)
```

## ILE spec

Modern ILE programs are usually built of a module, a service program, and a bind dir. Let's do that.

The outer hooks are called **globals**, they are executed before or after any specific target is compiled. Also, every target can also have its **specific** hooks.

Targets and commands are processed in linear order. First we set up the environment, then, compile the modules, create the service program with the modules. In the last target, add the service program to the bind dir, do the compilation, and then delete the bind dir to have a clean slate. Pretty slick.

For `SRCSTMF` params, the full path or the relative one can be specified. This, along with the `curlib` functionality, allows for easy context switching without being tied to a specific compilation instance.

In any case, you can easily change the cur dir with a global hook
``` yaml
before:
  CHGCURDIR:
    DIR: /home/BIGDAWG
```

In this spec, first, we set the library list and cur lib, then, create the bind dir. All this is done before processing any target. 

Since we already set the curlib, we can simply specify **curlib** for the targets libraries.

```yaml
before:
  chglibl: 
    LIBL: robkraudy2
  chgcurlib: 
    CURLIB: robkraudy2
  CrtBndDir:
    BNDDIR: BNDHELLO

targets:  
  "curlib.modhello1.module.rpgle":
    params:
      SRCSTMF: builds/rpg_language/chapter_1/qrpglesrc/modhello1.module.rpgle

  "*curlib.modhello2.module.rpgle":
    params:
      SRCSTMF: /home/ROBKRAUDY/builds/rpg_language/chapter_1/qrpglesrc/modhello2.module.rpgle

  "curlib.srvhello.srvpgm.bnd":
    params:
      SRCSTMF: /home/ROBKRAUDY/sources/ROBKRAUDY2/QSRVSRC/SRVHELLO.BND
      MODULE:
        - modhello1
        - modhello2

  curlib.hello5.pgm.rpgle:
    before:
      AddBndDirE:
        BndDir: BNDHELLO
        Obj: SRVHELLO

    after: 
      DltObj: 
        OBJ: BNDHELLO
        OBJTYPE: BndDir

    params:
      SRCSTMF: /home/ROBKRAUDY/builds/rpg_language/chapter_2/qrpglesrc/hello5.pgm.rpgle
      OPTION: EVENTF
      DBGVIEW: Source
      TGTCCSID: job

```

Save the yaml file and call the compiler with the yaml path as parameter. Here, we omitted the `-xv` flag to get a reduced log.

```
java -jar MasterCompiler-1.0-SNAPSHOT.jar -f /home/ROBKRAUDY/yaml/robkraudy1.hello.pgm.rpgle.yaml
```

Log
``` diff
+ 06:39:09.400 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: CHGLIBL LIBL(robkraudy2)
+ 06:39:09.414 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: CHGCURLIB CURLIB(robkraudy2)
+ 06:39:09.441 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: CRTBNDDIR BNDDIR(*CURLIB/BNDHELLO)
+ 06:39:10.892 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: CRTRPGMOD MODULE(*CURLIB/modhello1) SRCSTMF(''/home/ROBKRAUDY/builds/rpg_language/chapter_1/qrpglesrc/hello2.nomain.module.rpgle'') OPTION(*EVENTF) DBGVIEW(*ALL) OPTIMIZE(*NONE) LANGID(*JOBRUN) REPLACE(*YES) TGTRLS(V7R5M0) PRFDTA(*NOCOL) STGMDL(*INHERIT) TGTCCSID(*JOB)
+ 06:39:12.160 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: CRTRPGMOD MODULE(*CURLIB/modhello2) SRCSTMF(''/home/ROBKRAUDY/builds/rpg_language/chapter_1/qrpglesrc/bye.nomain.module.rpgle'') OPTION(*EVENTF) DBGVIEW(*ALL) OPTIMIZE(*NONE) LANGID(*JOBRUN) REPLACE(*YES) TGTRLS(V7R5M0) PRFDTA(*NOCOL) STGMDL(*INHERIT) TGTCCSID(*JOB)
+ 06:39:13.258 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: CRTSRVPGM SRVPGM(*CURLIB/SRVHELLO) MODULE(*LIBL/modhello1 *LIBL/modhello2) SRCSTMF(''/home/ROBKRAUDY/sources/ROBKRAUDY2/QSRVSRC/SRVHELLO.BND'') BNDSRVPGM(*NONE) ACTGRP(*CALLER) OPTION(*EVENTF) USRPRF(*USER) REPLACE(*YES) TGTRLS(V7R5M0) STGMDL(*SNGLVL)
+ 06:39:13.266 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: ADDBNDDIRE BNDDIR(*CURLIB/BNDHELLO) OBJ(*LIBL/SRVHELLO)
+ 06:39:14.561 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: CRTBNDRPG PGM(*CURLIB/HELLO5) SRCSTMF(''/home/ROBKRAUDY/builds/rpg_language/chapter_2/qrpglesrc/hello5.pgm.rpgle'') DFTACTGRP(*NO) ACTGRP(QILE) STGMDL(*SNGLVL) OPTION(*EVENTF) DBGVIEW(*SOURCE) REPLACE(*YES) USRPRF(*USER) TGTRLS(V7R5M0) PRFDTA(*NOCOL) TGTCCSID(*JOB)
+ 06:39:14.569 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: DLTOBJ OBJ(*LIBL/BNDHELLO) OBJTYPE(*BNDDIR)
```

## Error spec

In real life, things burn and fall off the cliff so, it would be wise to take that into account. When that happens, we need to have the necessary useful information to know where things went sideway. 

When an exception occurs, we follow the unix philosophy: fail loud and early. All exceptions are raised and all the context information (if available) is encapsulated to show the full stack and state of things before the error. 

This spec has a default hooks to set default params values for every target in the spec. If the params is not valid for a compilation command, it is just ignored.

This example will give us an error because MC will look for the source member inside QRPGLESRC (default source pf) and it does not exists.

```yaml
defaults:
  tgtrls: V7R5M0
  dbgview: *SOURCE
  replace: *YES

targets:
  "robkraudy1.BASIC4002.pgm.rpgle":
    params:
      TEXT: "Overridden text"
      SRCFILE: "ROBKRAUDY1/PRACTICAS"
```

Save the yaml and call the compiler, this time with the debug and verbose flags `-xv`

```
java -jar MasterCompiler-1.0-SNAPSHOT.jar -xv -f /home/ROBKRAUDY/yaml/pgm_rpgle_error.yaml
```

Look at that, a beautiful-looking log. Look how we have a full view of everything that happened before, during, and after the exception. No context was lost from the moment it occurred to the moment that it bubbled up to the top of the stack for logging. That is very important and valuable functionality.

```diff
+06:46:47.184 [main] INFO  c.g.kraudy.compiler.MasterCompiler - 
Library list: 
ROBKRAUDY1
QTEMP

+06:46:47.192 [main] INFO  c.g.kraudy.compiler.MasterCompiler - Building: ROBKRAUDY1.BASIC4002.PGM.RPGLE
+06:46:48.220 [main] INFO  c.g.kraudy.compiler.ObjectDescriptor - Found pgm object compilation info 'ROBKRAUDY1.BASIC4002.PGM.RPGLE
+06:46:48.225 [main] INFO  com.github.kraudy.compiler.Migrator - Migrating source member to stream file
-06:46:48.244 [main] ERROR c.g.kraudy.compiler.CommandExecutor - 
Command failed: CPYTOSTMF FROMMBR(''/QSYS.lib/ROBKRAUDY1.lib/QRPGLESRC.file/BASIC4002.mbr'') TOSTMF(''/home/ROBKRAUDY/sources/ROBKRAUDY1.BASIC4002.PGM.RPGLE'') STMFOPT(*REPLACE) STMFCCSID(1208) ENDLINFMT(*LF)
-06:46:48.263 [main] ERROR c.g.kraudy.compiler.MasterCompiler - Target compilation failed: ROBKRAUDY1.BASIC4002.PGM.RPGLE
-06:46:48.263 [main] ERROR c.g.kraudy.compiler.MasterCompiler - Compilation failed
-06:46:48.265 [main] ERROR c.g.kraudy.compiler.MasterCompiler - 
CompilerException Details:
- Message: System command failed
- System Command: CPYTOSTMF
- Cause: 
Chained 
CompilerException Details:
- Message: Command execution failed
- Command: CPYTOSTMF FROMMBR(''/QSYS.lib/ROBKRAUDY1.lib/QRPGLESRC.file/BASIC4002.mbr'') TOSTMF(''/home/ROBKRAUDY/sources/ROBKRAUDY1.BASIC4002.PGM.RPGLE'') STMFOPT(*REPLACE) STMFCCSID(1208) ENDLINFMT(*LF)
- Time: 2025-12-28 06:46:48.228431
- Joblog Messages: 

Joblog info
+2025-12-28 06:46:48  | CPFA0A9    | 40   | Object not found.  Object is /QSYS.lib/ROBKRAUDY1.lib/QRPGLESRC.file/BASIC4002.mbr.
+2025-12-28 06:46:48  | CPFA097    | 40   | Object not copied.  Object is /QSYS.lib/ROBKRAUDY1.lib/QRPGLESRC.file/BASIC4002.mbr.

- Cause: java.sql.SQLException: [CPFA097] Object not copied.  Object is /QSYS.lib/ROBKRAUDY1.lib/QRPGLESRC.file/BASIC4002.mbr.
-java.sql.SQLException: [CPFA097] Object not copied.  Object is /QSYS.lib/ROBKRAUDY1.lib/QRPGLESRC.file/BASIC4002.mbr.
-        at com.ibm.as400.access.JDError.createSQLExceptionSubClass(JDError.java:982)
-        at com.ibm.as400.access.JDError.throwSQLException(JDError.java:738)
-        at com.ibm.as400.access.AS400JDBCStatement.commonPrepare(AS400JDBCStatement.java:1634)
-        at com.ibm.as400.access.AS400JDBCStatement.execute(AS400JDBCStatement.java:2148)
-        at com.github.kraudy.compiler.CommandExecutor.executeCommand(CommandExecutor.java:111)
-        at com.github.kraudy.compiler.CommandExecutor.executeCommand(CommandExecutor.java:55)
-        at com.github.kraudy.compiler.Migrator.migrateMemberToStreamFile(Migrator.java:110)
-        at com.github.kraudy.compiler.Migrator.migrateSource(Migrator.java:51)
-        at com.github.kraudy.compiler.MasterCompiler.buildTargets(MasterCompiler.java:178)
-        at com.github.kraudy.compiler.MasterCompiler.build(MasterCompiler.java:94)
-        at com.github.kraudy.compiler.MasterCompiler.main(MasterCompiler.java:288)


+06:46:48.265 [main] INFO  c.g.kraudy.compiler.MasterCompiler - 
Chain of commands: CPYTOSTMF FROMMBR(''/QSYS.lib/ROBKRAUDY1.lib/QRPGLESRC.file/BASIC4002.mbr'') TOSTMF(''/home/ROBKRAUDY/sources/ROBKRAUDY1.BASIC4002.PGM.RPGLE'') STMFOPT(*REPLACE) STMFCCSID(1208) ENDLINFMT(*LF)
```