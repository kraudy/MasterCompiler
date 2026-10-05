# MasterCompiler demo

Four small sources that build five objects, all named `MCD*` so they are easy to recognise and remove:

| Object | Type | From |
|--------|------|------|
| `MCDITEM` | SQL table | `MCDITEM.table.sql` |
| `MCDCALC` | `*MODULE` and `*SRVPGM` | `MCDCALC.srvpgm.rpgle` (+ prototype `mcdcalc_p.include.rpgle`) |
| `MCDDEMO` | `*BNDDIR` | `mc-base.yaml` (holds `MCDCALC`) |
| `MCDHELLO` | `*PGM` | `MCDHELLO.pgm.sqlrpgle`: reads `MCDITEM` with SQL, calls `MCDCALC` |

MC works out the order (table, module, service program, binding directory, program) from the sources.
The objects go into your current library. Remove them with MC's `clean` tool (ask Copilot to "clean up
the demo"), or `java -jar MasterCompiler.jar --project . -c` from the CLI.
