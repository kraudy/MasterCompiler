# Spec

To define the flow of compilation, we use a yaml based spec. Here, you have the flexibility to define global or specific per [compilation target](./TargetKey.md) hooks to be executed before, after, or in case of success or failure during, before, or after compiling any target. This affords a lot of flexibility in a clean manner.

Many targets can be defined inside a spec and set the compilation environment for each one of them and also for the whole flow. All in the same file and readable in one swoop.

Every command and param is validated at deserialization, so if you have wrongly typed params or the wrong param for a command, MC will instantly tell you, following the spirit of fail loud and early. This also helps to keep the code clean since no more syntactic validation is needed and everything is already mapped to Java objects (which are also data structures... heap allocation).


[Spec class](../src/main/java/com/github/kraudy/compiler/BuildSpec.java) 

[Hooks deserializer class](../src/main/java/com/github/kraudy/compiler/CommandMapDeserializer.java) 

[Compilation params deserializer class](../src/main/java/com/github/kraudy/compiler/ParamMapDeserializer.java) 

Full spec template:

```yaml
defaults: {}  # optional | Global compilation command params

before: {}    # optional | Global pre-compilation system commands

after: {}     # optional | Global post-compilation system commands

success: {}   # optional | Global on success system commands

failure: {}   # optional | Global on failure system commands

targets:      # Required | Ordered sequence of compilation targets. At leas one target is required in the spec

  # Ile rpgle key
  curlib.hello.pgm.rgple:  

    before: {}            # optional | Per-target pre-compilation system commands

    params: {}            # optional | Target's compilation params

    after: {}             # optional | Per-target post-compilation system commands

    success: {}           # optional | Per-target on success system commands

    failure: {}           # optional | Per-target on failure system commands

  curlib.hello.module.rgple:  {} # A second compilation target

```

Examples 
- [Simplest spec](#simplest-spec)
- [ILE spec](#ile-spec)

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

You can also define a global default hook to set default params values for every target in the spec. If the params is not valid for a compilation command, it is just ignored.

```yaml
defaults:
  tgtrls: V7R5M0
  dbgview: *SOURCE
  replace: *YES
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

## Multiple keys

If you have a hook like this

```yaml
before:
  OvrDbf: 
    File: CUST
    ToFile: CUSTOMER
  OvrDbf: 
    File: INST
    ToFile: IWS_INSTAN
  OvrDbf: 
    File: SERV
    ToFile: IWS_SERVIC
```

Yaml will silently override each OvrDbf since they are the same key and you will only get `OVRDBF FILE(SERV) TOFILE(*LIBL/IWS_SERVIC)` executed.

To solve this, use a list for each command in the hook.

```yaml
before:
  - OvrDbf: 
      File: CUST
      ToFile: CUSTOMER
  - OvrDbf: 
      File: INST
      ToFile: IWS_INSTAN
  - OvrDbf: 
      File: SERV
      ToFile: IWS_SERVIC
```

With this change, you will get all the commands executed: 

``` diff
+ OVRDBF FILE(CUST) TOFILE(*LIBL/CUSTOMER) => OVRDBF FILE(INST) TOFILE(*LIBL/IWS_INSTAN) => OVRDBF FILE(SERV) TOFILE(*LIBL/IWS_SERVIC)
```