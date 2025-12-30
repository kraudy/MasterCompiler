# Migration

What's the biggest problem for modern version control on the IBM i? Its object system; which is also what makes the system so robust, integrated, and efficient. What a conundrum.

Specifically, the source members are unique tables (partitioned tables) that only exist on the IBM i operating system. Luckily there is also the PASE IFS file system.

MC pushes for all the source files to be stored in the IFS, where tools for version control like Git can be used.

Automatic migration of source members is performed. For OPM object, the reversed migration is done. If there is any conflict between params, MC does the resolution under the hood, and the changes are shown in the params history. Every change made to a param is tracked individually.

If you want to migrate large code bases in sublinear time, I made this other tool that you may find useful: [SourceMigrator](https://github.com/kraudy/SourceMigrator)

[Migrator class](../src/main/java/com/github/kraudy/compiler/Migrator.java) 

Here is the log of a source member migration.

```diff
+ 23:45:31.051 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: CPYTOSTMF FROMMBR(''/QSYS.lib/ROBKRAUDY1.lib/QRPGLESRC.file/HELLO.mbr'') TOSTMF(''/home/ROBKRAUDY/sources/ROBKRAUDY1.HELLO.PGM.RPGLE'') STMFOPT(*REPLACE) STMFCCSID(1208) ENDLINFMT(*LF)

+ 23:45:31.486 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: CRTBNDRPG PGM(*CURLIB/HELLO) SRCSTMF(''/home/ROBKRAUDY/sources/ROBKRAUDY1.HELLO.PGM.RPGLE'') DFTACTGRP(*NO) ACTGRP(QILE) STGMDL(*SNGLVL) OPTION(*EVENTF) DBGVIEW(*ALL) REPLACE(*YES) USRPRF(*USER) TGTRLS(V7R5M0) PRFDTA(*NOCOL) TGTCCSID(*JOB)
```

Reverse migration

```diff
+23:55:08.378 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: CPYFRMSTMF FROMSTMF(''/home/ROBKRAUDY/sources/ROBKRAUDY1/PRACTICAS/BRICKR.RPG'') TOMBR(''/QSYS.lib/ROBKRAUDY1.lib/QRPGSRC.file/BRICKR.mbr'') MBROPT(*REPLACE) CVTDTA(*AUTO) STMFCODPAG(1208)

+3:55:08.548 [main] INFO c.g.kraudy.compiler.CommandExecutor - 
Command successful: CRTRPGPGM PGM(*CURLIB/BRICKR) SRCFILE(ROBKRAUDY1/QRPGSRC) SRCMBR(BRICKR) TEXT(''Brick-a-Spell'') OPTION(*LSTDBG) GENOPT(*LIST) REPLACE(*YES) TGTRLS(*CURRENT) USRPRF(*USER)
```