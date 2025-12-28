# Target key

Leveragin the enum base map functinality, MC maps uses the  **objectType** and **sourceType** of an object to inffer its compilation command and from this command, its pattern of params.

This behavior, conveninently let us decribe objects in a consice, unique and intuitive way called **target key** (or compilation target) of the form **library.objectName.objectType.sourceType**. 

## ILE keys

| Target Key | Compilation command | Pattern |
|----------|----------|----------|
| mylib.hello.**pgm.rpgle**           | `CRTBNDRPG`  | **Rpgle_Pgm_Pattern** 
| mylib.hello.**pgm.clle**            | `CRTBNDCL`   | **Clle_Pgm_Pattern**
| mylib.sqlhello.**pgm.sqlrpgle**     | `CRTSQLRPGI` | **SqlRpgle_Pgm_Mod_Pattern**
| mylib.modhello.**module.rpgle**     | `CRTRPGMOD`  | **Rpgle_Mod_Pattern**
| mylib.modhello.**module.sqlrpgle**  | `CRTSQLRPGI` | **SqlRpgle_Pgm_Mod_Pattern**
| mylib.modhello.**module.clle**      | `CRTCLMOD`   | **Clle_Mod_Pattern**
| mylib.srvhello.**srvpgm.bnd**       | `CRTSRVPGM`  | **Bnd_Srvpgm_Pattern**

## OPM keys

| Target Key | Compilation command | Pattern |
|----------|----------|----------|
| mylib.hello.**pgm.rpg**             | `CRTRPGPGM`  | **Rpg_Pgm_Pattern**
| mylib.hello.**pgm.clp**             | `CRTCLPGM`   | **Clp_Pgm_Pattern**

## SQL keys

| Target Key | Compilation command | Pattern |
|----------|----------|----------|
| mylib.hello.**table.sql**           | `RUNSQLSTM`  | **Sql_Pattern**
| mylib.hello.**index.sql**           | `RUNSQLSTM`  | **Sql_Pattern**
| mylib.hello.**view.sql**            | `RUNSQLSTM`  | **Sql_Pattern**
| mylib.hello.**alias.sql**           | `RUNSQLSTM`  | **Sql_Pattern**
| mylib.hello.**procedure.sql**       | `RUNSQLSTM`  | **Sql_Pattern**
| mylib.hello.**function.sql**        | `RUNSQLSTM`  | **Sql_Pattern**

## DDS keys

| Target Key | Compilation command | Pattern |
|----------|----------|----------|
| mylib.hello.**pf.dds**              | `CRTPF`      |  **Dds_Pf_Pattern**
| mylib.hello.**dspf.dds**            | `CRTDSPF`    |  **Dds_Dspf_Pattern**
| mylib.hello.**lf.dds**              | `CRTLF`      |  **Dds_Lf_Pattern**
| mylib.hello.**prtf.dds**            | `CRTPRTF`    |  **Dds_Prtf_Pattern**

## CMD keys

| Target Key | Compilation command | Pattern |
|----------|----------|----------|
| mylib.hello.**cmd.cmd**             | `CRTCMD`     |  **Cmd_Pattern**

## MNU keys

| Target Key | Compilation command | Pattern |
|----------|----------|----------|
| mylib.hello.**mnu.mnu**             | `CRTMNU`     |  **Mnu_Pattern**

## QMQRY keys

| Target Key | Compilation command | Pattern |
|----------|----------|----------|
| mylib.hello.**qmqry.qmqry**         | `CRTQMQRY`   |  **Qmqry_Pattern**

