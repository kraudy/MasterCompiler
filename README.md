<div align="center">
<img src="./images/Master%20Compiler.jpg" alt="Master Compiler logo" style="display: block; margin: 0 auto; width: 40%; max-width: 800px; height: auto; object-fit: cover; aspect-ratio: 1 / 1;">
</div>

# Master Compiler

Master compilation tool for the IBM i platform. 

YAML-based, sensible, open-source, modern, clean, and elegant approach to automate and streamline object compilation.

Do you like building cool stuff for fun and freedom? Then this project is for you.

---

MasterCompiler (**MC**) aims to give the whole IBM i community a standard way to describe the compilation flow of objects and allow easy integration with any DevOps pipeline to be run locally (just upload the JAR file) or remotely in a Docker container.

---

### Requirements 

Java 8.

---

### Build 

* `git clone git@github.com:kraudy/MasterCompiler.git`
* `mvn clean package`. 

---

## Object Compilation

To compile an object, it must be defined as a **target key** of the form: **mylib.hello.pgm.rgple**.

| Target key | Library | Object name | Object type | Source type |
|----------|----------|----------|----------|----------|
| **mylib.hello.pgm.rgple** | **MYLIB** | **HELLO** | `PGM` | `RPGLE` |


The **ObjectType** and **SourceType** part of the **Target key** defines the compilation command to be executed.

| Object type | Source type | Compilation command | 
|----------|----------|----------|
| `PGM` | `RPGLE` | `CRTBNDRPG` |


[Target keys docs](./docs/TargetKey.md)

[Compilation Patterns docs](./docs/Patterns.md) 

## Compilation Flow

Besides the compilation itself, an object often requires other CL commands to be performed on the same job to set up the appropriate environment:

* Library list
* Files overrides
* Binding directories, etc.

**MC** calls this a **compilation flow**. Which can be easily defined in a Yaml spec file.

[Specs doc](./docs/Spec.md)

```yaml
before:       # optional | Global pre-compilation system commands
  chglibl:                # Set library list before targets compilation
    libl: mylib1 mylib2 
  chgcurlib:              # Set curlib before targets compilation
    CURLIB: mylib1
  CrtBndDir:              # Lets create a bind dir
    BNDDIR: BNDHELLO

targets:      # Required | Ordered sequence of compilation targets. At leas one target is required in the spec

  # Firt create the modules. We can specify curlib as the target library. Convenient.
  "curlib.modhello1.module.rpgle": 
    params:                # Per-target compilation command params
      SRCSTMF: /home/sources/hello2.modhello1.module.rpgle

  *curlib.modhello2.module.rpgle:
    params:
      SRCSTMF: /home/sources/modhello2.module.rpgle

  # Create service program with the modules
  "CurLib.srvhello.srvpgm.bnd":
    params:
      SRCSTMF: /home/sources/srvhello.srvpgm.bnd
      MODULE:
        - modhello1
        - modhello2

  # Ile rpgle with binding dir
  curlib.hello.pgm.rgple:  

    before:               # optional | Per-target pre-compilation system commands
      AddBndDirE:              # Add entry to bnd dir before target compiling
        BndDir: BNDHELLO
        Obj: SRVHELLO

    params:              
      # Target's compilation params
      SRCSTMF: /home/sources/HELLO.RPGLE
      DFTACTGRP: no
      ACTGRP: QILE
      STGMDL: Snglvl      # note how params names and values are flexible
      OPTION: EVENTF
      DBGVIEW: *source
      REPLACE: yes
      USRPRF: user
      TGTRLS: V7R5M0
      PRFDTA: nocol

after:        # optional | Global post-compilation system commands
  DltObj:                 # After all target are build, we delete the bind dir to have a clean slate
    OBJ: BNDHELLO
    OBJTYPE: BndDir
```

After defining the spec, just call **MasterCompiler** with unix style parameters

```bash
java -jar MasterCompiler-1.0-SNAPSHOT.jar -xv -f /home/user/clean_spec.yaml
```

# Cli 

Unix base style Cli with short and long parameters.

The spec route is always required `{-f, --file | /route/cool_spec.yaml}`

[Cli doc](./docs/Cli.md) 

## Parameter resolution and validation

Params are enum based, this allows for automatic param conflic resolution and validation in O(1) time along with instant validation at deserialization.

[Params doc](./docs/Params.md)

## Source migration

MC encourages git usage by automatically migrating source members to ifs stream files and stream files to source members for OPM objects. 

Can be disabled with flag `--no-migrate`

[Migrator doc](./docs/Migration.md)

## Object inspection

If available, metadata is extracted from compiled objects to infer compilation params.

[Object Descriptor doc](./docs/Inspection.md) 

## Traceability

Fully transparent and traceble flow of execution and changes.

[Traceability docs](./docs/Traceability.md)

## Contributing

Here is the rule: **You want to reduce complexity, increase readability, and provide functionality.**

Everything we do is a reflection of ourselves; even the world as we know it is a reflection of humanity... But that's a talk for another time.
