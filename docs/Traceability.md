# Traceability

In real life, things burn and fall off the cliff so, it would be wise to take it into account. 

When the rubber meets the road, we need to have the necessary useful information to know where things went sideway. 

**MC** implements a custom Exception [CompilerException](../src/main/java/com/github/kraudy/compiler/CompilerException.java) that allows for a full stack trace without loss of context when an error occurs.

* All messages and joblog entries are loged 
* Failed compilation spools are loged.
* Exceptions are handled and raised. No context loss with full stack trace.
* Fail early and loud.
* Each command executed is tracked in a chain of commands

When an exception occurs, we follow the unix philosophy: *fail loud and early*. All exceptions are raised and all the context information (if available) is encapsulated to show the full stack and state of things before the error. 

This example will give us an error because **MC** will look for the source member inside QRPGLESRC (default source pf) and it does not exists.

```yaml
targets:
  "robkraudy1.BASIC4002.pgm.rpgle":
    params:
      TEXT: "Overridden text"
      SRCFILE: "ROBKRAUDY1/PRACTICAS"
```

Now, look at that beautiful log. Note how we have a full view of everything that happened before, during, and after the exception. 

No context was lost from the moment it occurred to the moment that it bubbled up to the top of the stack for logging. That is very important and valuable functionality.

If an error spool is generated, **MC** also adds it to the log.

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