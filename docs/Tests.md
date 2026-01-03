# Tests

Something can be good and bad at different points in life, so don't trust Greek philosophers too much. 

The same applies for tests. They allow you to have a convenient verification method, but they also can tie you up to the current architecture. This may be what you want or not. So you need to take that into account.

On new projects, don't write a test till you have a good enough solid base; otherwise, you will be verifying the wrong thing, which leads to nothing.

## Unit tests

Since **MC** is built modularly, this allows us to test pure Java classes individually. These classes are easier to test because they don't need to mock anything or connect to any remote server or any other fancy stuff. Just instantiate the object, perform some operation, and validate the result. 

These classes are very important because they contain core logic functionality.

* [ArgParser Tests](../src/test/java/com/github/kraudy/compiler/ArgParserTest.java)
* [ParamMap Test](../src/test/java/com/github/kraudy/compiler/ParamMapTest.java)
* [ParamValue Test](../src/test/java/com/github/kraudy/compiler/ParamValueTest.java)
* [TargetKey Test](../src/test/java/com/github/kraudy/compiler/TargetKeyTest.java)
* [Utilities Test](../src/test/java/com/github/kraudy/compiler/UtilitiesTest.java)

These test are executed automatically with every `mvn clean package`. 

## Integration tests

Integration tests are a different beast, especially on IBM i-related projects. 

Here is the problem: **You want to test the full application stack; it needs to be reproducible anywhere and by anyone. Run on any remote IBM i server and be executed automatically from your laptop**.

Quite a challenge, right? **MC** just solved this problem.

Create a `.env` file like this at project root

```bash
IBMI_HOSTNAME=BIGIRON.COM
IBMI_USERNAME=BIGBOY
IBMI_PASSWORD=BIGMONEY
```

Then execute `mvn clean verify -Pintegration`

That's it.

[StreamCompilation Integration Test](../src/test/java/com/github/kraudy/compiler/StreamCompilationIT.java)

* Connects to a real IBM I
* Upload source files from  [../src/test/resources/sources](../src/test/resources/sources)
* Deserialize spec from [../src/test/resources/yaml](../src/test/resources/yaml)
* Execute compilation flow remotely
* Generate log
* Remove created objects

Here is a full integration test log

```diff
[INFO] Running com.github.kraudy.compiler.StreamCompilationIT
+20:44:24.356 [main] INFO  c.g.kraudy.compiler.MasterCompiler - Executing global before: 1commands found
+20:44:24.993 [main] INFO  com.github.kraudy.compiler.ParamMap - 
Command CHGLIBL summary
LIBL:[[INIT], ROBKRAUDY1]

+20:44:25.475 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: CHGLIBL LIBL(ROBKRAUDY1)
+20:44:26.199 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Joblog info
2026-01-03 03:44:25  | CPC2101    | 0    | Library list changed.

+20:44:26.837 [main] INFO  c.g.kraudy.compiler.MasterCompiler - 
Library list: 
ROBKRAUDY1

+20:44:26.838 [main] INFO  c.g.kraudy.compiler.MasterCompiler - Building: CURLIB.HELLO.PGM.RPGLE
+20:44:26.838 [main] INFO  c.g.kraudy.compiler.MasterCompiler - Resolving curlib
+20:44:29.943 [main] INFO  c.g.kraudy.compiler.ObjectDescriptor - Could not retrieve pgm object compilation info ROBKRAUDY1.HELLO.PGM.RPGLE
+20:44:30.890 [main] INFO  com.github.kraudy.compiler.ParamMap - 
Command CRTBNDRPG summary
PGM:[[INIT], CURLIB/HELLO, *CURLIB/HELLO]
SRCSTMF:[[INIT], ''/home/ROBKRAUDY/1767408254489_HELLO.RPGLE'', ''/home/ROBKRAUDY/1767408254489_HELLO.RPGLE'']
TEXT:[[INIT], ''Hello from integration test'']
OPTION:[[INIT], *EVENTF]
DBGVIEW:[[INIT], *ALL]
REPLACE:[[INIT], *YES]
TGTCCSID:[[INIT], *JOB]

+20:44:30.891 [main] INFO  com.github.kraudy.compiler.ParamMap - 
Command CRTBNDRPG summary
PGM:[[INIT], CURLIB/HELLO, *CURLIB/HELLO]
SRCFILE:[[INIT], CURLIB/QRPGLESRC, [REMOVED]]
SRCMBR:[[INIT], HELLO, [REMOVED]]
SRCSTMF:[[INIT], ''/home/ROBKRAUDY/1767408254489_HELLO.RPGLE'', ''/home/ROBKRAUDY/1767408254489_HELLO.RPGLE'']
TEXT:[[INIT], ''Hello from integration test'']
OPTION:[[INIT], *EVENTF]
DBGVIEW:[[INIT], *ALL]
REPLACE:[[INIT], *YES]
TGTCCSID:[[INIT], *JOB]

+20:44:31.569 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: CRTBNDRPG PGM(*CURLIB/HELLO) SRCSTMF(''/home/ROBKRAUDY/1767408254489_HELLO.RPGLE'') TEXT(''Hello from integration test'') OPTION(*EVENTF) DBGVIEW(*ALL) REPLACE(*YES) TGTCCSID(*JOB)
+20:44:32.207 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Joblog info
2026-01-03 03:44:31  | RNS9304    | 0    | Program HELLO placed in library ROBKRAUDY1. 00 highest severity. Created on 26-01-03 at 03:44:31.

+20:44:32.207 [main] INFO  c.g.kraudy.compiler.MasterCompiler - 
Chain of commands: CHGLIBL LIBL(ROBKRAUDY1) => CRTBNDRPG PGM(*CURLIB/HELLO) SRCSTMF(''/home/ROBKRAUDY/1767408254489_HELLO.RPGLE'') TEXT(''Hello from integration test'') OPTION(*EVENTF) DBGVIEW(*ALL) REPLACE(*YES) TGTCCSID(*JOB)
+20:44:33.821 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: DLTOBJ OBJ(*CURLIB/HELLO) OBJTYPE(*PGM)
+20:44:38.633 [main] INFO  c.g.kraudy.compiler.MasterCompiler - Executing global before: 1commands found
+20:44:39.260 [main] INFO  com.github.kraudy.compiler.ParamMap - 
Command CHGLIBL summary
LIBL:[[INIT], ROBKRAUDY1]

+20:44:39.736 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: CHGLIBL LIBL(ROBKRAUDY1)
+20:44:40.370 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Joblog info
2026-01-03 03:44:39  | CPC2101    | 0    | Library list changed.

+20:44:41.006 [main] INFO  c.g.kraudy.compiler.MasterCompiler - 
Library list: 
ROBKRAUDY1

+20:44:41.006 [main] INFO  c.g.kraudy.compiler.MasterCompiler - Building: CURLIB.HELLO.MODULE.RPGLE
+20:44:41.007 [main] INFO  c.g.kraudy.compiler.MasterCompiler - Resolving curlib
+20:44:43.074 [main] INFO  c.g.kraudy.compiler.ObjectDescriptor - Could not retrieve module compilation info ROBKRAUDY1.HELLO.MODULE.RPGLE
+20:44:44.044 [main] INFO  com.github.kraudy.compiler.ParamMap - 
Command CRTRPGMOD summary
MODULE:[[INIT], CURLIB/HELLO, *CURLIB/HELLO]
SRCSTMF:[[INIT], ''/home/ROBKRAUDY/1767408273845_HELLO.RPGLE'', ''/home/ROBKRAUDY/1767408273845_HELLO.RPGLE'']
OPTION:[[INIT], *EVENTF]
DBGVIEW:[[INIT], *ALL]
REPLACE:[[INIT], *YES]
TGTCCSID:[[INIT], *JOB]

+20:44:44.045 [main] INFO  com.github.kraudy.compiler.ParamMap - 
Command CRTRPGMOD summary
MODULE:[[INIT], CURLIB/HELLO, *CURLIB/HELLO]
SRCFILE:[[INIT], CURLIB/QRPGLESRC, [REMOVED]]
SRCMBR:[[INIT], HELLO, [REMOVED]]
SRCSTMF:[[INIT], ''/home/ROBKRAUDY/1767408273845_HELLO.RPGLE'', ''/home/ROBKRAUDY/1767408273845_HELLO.RPGLE'']
OPTION:[[INIT], *EVENTF]
DBGVIEW:[[INIT], *ALL]
REPLACE:[[INIT], *YES]
TGTCCSID:[[INIT], *JOB]

+20:44:44.691 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: CRTRPGMOD MODULE(*CURLIB/HELLO) SRCSTMF(''/home/ROBKRAUDY/1767408273845_HELLO.RPGLE'') OPTION(*EVENTF) DBGVIEW(*ALL) REPLACE(*YES) TGTCCSID(*JOB)
+20:44:45.327 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Joblog info
2026-01-03 03:44:44  | RNS9305    | 0    | Module HELLO placed in library ROBKRAUDY1. 00 highest severity. Created on 26-01-03 at 03:44:44.

+20:44:45.327 [main] INFO  c.g.kraudy.compiler.MasterCompiler - Building: CURLIB.BYE.MODULE.RPGLE
+20:44:45.327 [main] INFO  c.g.kraudy.compiler.MasterCompiler - Resolving curlib
+20:44:47.589 [main] INFO  c.g.kraudy.compiler.ObjectDescriptor - Could not retrieve module compilation info ROBKRAUDY1.BYE.MODULE.RPGLE
+20:44:48.535 [main] INFO  com.github.kraudy.compiler.ParamMap - 
Command CRTRPGMOD summary
MODULE:[[INIT], CURLIB/BYE, *CURLIB/BYE]
SRCSTMF:[[INIT], ''/home/ROBKRAUDY/1767408276024_BYE.RPGLE'', ''/home/ROBKRAUDY/1767408276024_BYE.RPGLE'']
OPTION:[[INIT], *EVENTF]
DBGVIEW:[[INIT], *ALL]
REPLACE:[[INIT], *YES]
TGTCCSID:[[INIT], *JOB]

+20:44:48.536 [main] INFO  com.github.kraudy.compiler.ParamMap - 
Command CRTRPGMOD summary
MODULE:[[INIT], CURLIB/BYE, *CURLIB/BYE]
SRCFILE:[[INIT], CURLIB/QRPGLESRC, [REMOVED]]
SRCMBR:[[INIT], BYE, [REMOVED]]
SRCSTMF:[[INIT], ''/home/ROBKRAUDY/1767408276024_BYE.RPGLE'', ''/home/ROBKRAUDY/1767408276024_BYE.RPGLE'']
OPTION:[[INIT], *EVENTF]
DBGVIEW:[[INIT], *ALL]
REPLACE:[[INIT], *YES]
TGTCCSID:[[INIT], *JOB]

+20:44:49.089 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: CRTRPGMOD MODULE(*CURLIB/BYE) SRCSTMF(''/home/ROBKRAUDY/1767408276024_BYE.RPGLE'') OPTION(*EVENTF) DBGVIEW(*ALL) REPLACE(*YES) TGTCCSID(*JOB)
+20:44:50.090 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Joblog info
2026-01-03 03:44:48  | RNS9305    | 0    | Module BYE placed in library ROBKRAUDY1. 00 highest severity. Created on 26-01-03 at 03:44:48.

+20:44:50.090 [main] INFO  c.g.kraudy.compiler.MasterCompiler - 
Chain of commands: CHGLIBL LIBL(ROBKRAUDY1) => CRTRPGMOD MODULE(*CURLIB/HELLO) SRCSTMF(''/home/ROBKRAUDY/1767408273845_HELLO.RPGLE'') OPTION(*EVENTF) DBGVIEW(*ALL) REPLACE(*YES) TGTCCSID(*JOB) => CRTRPGMOD MODULE(*CURLIB/BYE) SRCSTMF(''/home/ROBKRAUDY/1767408276024_BYE.RPGLE'') OPTION(*EVENTF) DBGVIEW(*ALL) REPLACE(*YES) TGTCCSID(*JOB)
+20:44:52.202 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: DLTOBJ OBJ(*CURLIB/HELLO) OBJTYPE(*MODULE)
+20:44:53.310 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: DLTOBJ OBJ(*CURLIB/BYE) OBJTYPE(*MODULE)
+ 20:45:00.209 [main] INFO  c.g.kraudy.compiler.MasterCompiler - Executing global before: 1commands found
+ 20:45:00.838 [main] INFO  com.github.kraudy.compiler.ParamMap - 
Command CHGLIBL summary
LIBL:[[INIT], ROBKRAUDY1]

+ 20:45:01.344 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: CHGLIBL LIBL(ROBKRAUDY1)
+ 20:45:01.987 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Joblog info
2026-01-03 03:45:01  | CPC2101    | 0    | Library list changed.

+ 20:45:02.653 [main] INFO  c.g.kraudy.compiler.MasterCompiler - 
Library list: 
ROBKRAUDY1

+ 20:45:02.653 [main] INFO  c.g.kraudy.compiler.MasterCompiler - Building: CURLIB.HELLO.MODULE.RPGLE
+ 20:45:02.653 [main] INFO  c.g.kraudy.compiler.MasterCompiler - Resolving curlib
+ 20:45:05.527 [main] INFO  c.g.kraudy.compiler.ObjectDescriptor - Could not retrieve module compilation info ROBKRAUDY1.HELLO.MODULE.RPGLE
+ 20:45:06.481 [main] INFO  com.github.kraudy.compiler.ParamMap - 
Command CRTRPGMOD summary
MODULE:[[INIT], CURLIB/HELLO, *CURLIB/HELLO]
SRCSTMF:[[INIT], ''/home/ROBKRAUDY/1767408293320_HELLO.RPGLE'', ''/home/ROBKRAUDY/1767408293320_HELLO.RPGLE'']
OPTION:[[INIT], *EVENTF]
DBGVIEW:[[INIT], *ALL]
REPLACE:[[INIT], *YES]
TGTCCSID:[[INIT], *JOB]

+ 20:45:06.481 [main] INFO  com.github.kraudy.compiler.ParamMap - 
Command CRTRPGMOD summary
MODULE:[[INIT], CURLIB/HELLO, *CURLIB/HELLO]
SRCFILE:[[INIT], CURLIB/QRPGLESRC, [REMOVED]]
SRCMBR:[[INIT], HELLO, [REMOVED]]
SRCSTMF:[[INIT], ''/home/ROBKRAUDY/1767408293320_HELLO.RPGLE'', ''/home/ROBKRAUDY/1767408293320_HELLO.RPGLE'']
OPTION:[[INIT], *EVENTF]
DBGVIEW:[[INIT], *ALL]
REPLACE:[[INIT], *YES]
TGTCCSID:[[INIT], *JOB]

+ 20:45:07.081 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: CRTRPGMOD MODULE(*CURLIB/HELLO) SRCSTMF(''/home/ROBKRAUDY/1767408293320_HELLO.RPGLE'') OPTION(*EVENTF) DBGVIEW(*ALL) REPLACE(*YES) TGTCCSID(*JOB)
+ 20:45:07.717 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Joblog info
2026-01-03 03:45:06  | RNS9305    | 0    | Module HELLO placed in library ROBKRAUDY1. 00 highest severity. Created on 26-01-03 at 03:45:06.

+ 20:45:07.717 [main] INFO  c.g.kraudy.compiler.MasterCompiler - Building: CURLIB.BYE.MODULE.RPGLE
+ 20:45:07.717 [main] INFO  c.g.kraudy.compiler.MasterCompiler - Resolving curlib
+ 20:45:10.544 [main] INFO  c.g.kraudy.compiler.ObjectDescriptor - Could not retrieve module compilation info ROBKRAUDY1.BYE.MODULE.RPGLE
+ 20:45:11.524 [main] INFO  com.github.kraudy.compiler.ParamMap - 
Command CRTRPGMOD summary
MODULE:[[INIT], CURLIB/BYE, *CURLIB/BYE]
SRCSTMF:[[INIT], ''/home/ROBKRAUDY/1767408295559_BYE.RPGLE'', ''/home/ROBKRAUDY/1767408295559_BYE.RPGLE'']
OPTION:[[INIT], *EVENTF]
DBGVIEW:[[INIT], *ALL]
REPLACE:[[INIT], *YES]
TGTCCSID:[[INIT], *JOB]

+ 20:45:11.524 [main] INFO  com.github.kraudy.compiler.ParamMap - 
Command CRTRPGMOD summary
MODULE:[[INIT], CURLIB/BYE, *CURLIB/BYE]
SRCFILE:[[INIT], CURLIB/QRPGLESRC, [REMOVED]]
SRCMBR:[[INIT], BYE, [REMOVED]]
SRCSTMF:[[INIT], ''/home/ROBKRAUDY/1767408295559_BYE.RPGLE'', ''/home/ROBKRAUDY/1767408295559_BYE.RPGLE'']
OPTION:[[INIT], *EVENTF]
DBGVIEW:[[INIT], *ALL]
REPLACE:[[INIT], *YES]
TGTCCSID:[[INIT], *JOB]

+ 20:45:12.173 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: CRTRPGMOD MODULE(*CURLIB/BYE) SRCSTMF(''/home/ROBKRAUDY/1767408295559_BYE.RPGLE'') OPTION(*EVENTF) DBGVIEW(*ALL) REPLACE(*YES) TGTCCSID(*JOB)
+ 20:45:12.818 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Joblog info
2026-01-03 03:45:12  | RNS9305    | 0    | Module BYE placed in library ROBKRAUDY1. 00 highest severity. Created on 26-01-03 at 03:45:11.

+ 20:45:12.818 [main] INFO  c.g.kraudy.compiler.MasterCompiler - Building: CURLIB.HELLO.PGM.RPGLE
+ 20:45:12.818 [main] INFO  c.g.kraudy.compiler.MasterCompiler - Resolving curlib
+ 20:45:15.453 [main] INFO  c.g.kraudy.compiler.ObjectDescriptor - Could not retrieve pgm object compilation info ROBKRAUDY1.HELLO.PGM.RPGLE
+ 20:45:16.396 [main] INFO  com.github.kraudy.compiler.ParamMap - 
Command CRTBNDRPG summary
PGM:[[INIT], CURLIB/HELLO, *CURLIB/HELLO]
SRCSTMF:[[INIT], ''/home/ROBKRAUDY/1767408297717_HELLO.RPGLE'', ''/home/ROBKRAUDY/1767408297717_HELLO.RPGLE'']
TEXT:[[INIT], ''Hello from integration test'']
OPTION:[[INIT], *EVENTF]
DBGVIEW:[[INIT], *ALL]
REPLACE:[[INIT], *YES]
TGTCCSID:[[INIT], *JOB]

+ 20:45:16.396 [main] INFO  com.github.kraudy.compiler.ParamMap - 
Command CRTBNDRPG summary
PGM:[[INIT], CURLIB/HELLO, *CURLIB/HELLO]
SRCFILE:[[INIT], CURLIB/QRPGLESRC, [REMOVED]]
SRCMBR:[[INIT], HELLO, [REMOVED]]
SRCSTMF:[[INIT], ''/home/ROBKRAUDY/1767408297717_HELLO.RPGLE'', ''/home/ROBKRAUDY/1767408297717_HELLO.RPGLE'']
TEXT:[[INIT], ''Hello from integration test'']
OPTION:[[INIT], *EVENTF]
DBGVIEW:[[INIT], *ALL]
REPLACE:[[INIT], *YES]
TGTCCSID:[[INIT], *JOB]

+ 20:45:17.050 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: CRTBNDRPG PGM(*CURLIB/HELLO) SRCSTMF(''/home/ROBKRAUDY/1767408297717_HELLO.RPGLE'') TEXT(''Hello from integration test'') OPTION(*EVENTF) DBGVIEW(*ALL) REPLACE(*YES) TGTCCSID(*JOB)
+ 20:45:17.686 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Joblog info
2026-01-03 03:45:16  | RNS9304    | 0    | Program HELLO placed in library ROBKRAUDY1. 00 highest severity. Created on 26-01-03 at 03:45:16.

+ 20:45:17.686 [main] INFO  c.g.kraudy.compiler.MasterCompiler - 
Chain of commands: CHGLIBL LIBL(ROBKRAUDY1) => CRTRPGMOD MODULE(*CURLIB/HELLO) SRCSTMF(''/home/ROBKRAUDY/1767408293320_HELLO.RPGLE'') OPTION(*EVENTF) DBGVIEW(*ALL) REPLACE(*YES) TGTCCSID(*JOB) => CRTRPGMOD MODULE(*CURLIB/BYE) SRCSTMF(''/home/ROBKRAUDY/1767408295559_BYE.RPGLE'') OPTION(*EVENTF) DBGVIEW(*ALL) REPLACE(*YES) TGTCCSID(*JOB) => CRTBNDRPG PGM(*CURLIB/HELLO) SRCSTMF(''/home/ROBKRAUDY/1767408297717_HELLO.RPGLE'') TEXT(''Hello from integration test'') OPTION(*EVENTF) DBGVIEW(*ALL) REPLACE(*YES) TGTCCSID(*JOB)
+ 20:45:21.454 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: DLTOBJ OBJ(*CURLIB/HELLO) OBJTYPE(*MODULE)
+ 20:45:22.583 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: DLTOBJ OBJ(*CURLIB/BYE) OBJTYPE(*MODULE)
+ 20:45:23.750 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: DLTOBJ OBJ(*CURLIB/HELLO) OBJTYPE(*PGM)
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 74.66 s -- in com.github.kraudy.compiler.StreamCompilationIT
```
