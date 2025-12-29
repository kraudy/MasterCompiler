<div align="center">
<img src="./images/Master%20Compiler.jpg" alt="Master Compiler logo" style="display: block; margin: 0 auto; width: 40%; max-width: 800px; height: auto; object-fit: cover; aspect-ratio: 1 / 1;">
</div>

# Master Compiler

Master compilation tool for the IBM i platform. 

YAML-based, sensible, open-source, modern, clean, and elegant approach to automate and streamline object compilation.

Do you like building cool stuff for fun and freedom? Then this project is for you.

---

This project aims to give the whole IBM i community a standard way to describe the compilation flow of objects and allow easy integration with any DevOps pipeline to be run locally (just upload the JAR file) or remotely in a Docker container.

Requirements? Java 8.

---

## Compilation

To compile an object, it must be defined as a unique key of the form 

* **library**.**objectName**.**objectType**.**sourceType**. 

* **mylib.hello.pgm.rgple** => `CRTBNDRPG`

The **objectType** and **sourceType** part of the target key define the compilation command to be executed.

[Target keys docs](./docs/TargetKey.md)

[Compilation Patterns docs](./docs/Patterns.md) 


Besides the compilation itself, an object often requires other CL commands to be performed on the same job to set up the appropriate environment: Library list, files overrides, binding directories, etc.

We can define a **flow of compilation** in a Yaml file.

[Specs doc](./docs/Spec.md)

```yaml
defaults: {}  # optional | Global compilation command params

before:       # optional | Global pre-compilation system commands
  chglibl:                # Set library list before targets compilation
    libl: mylib1 mylib2 
  chgcurlib:              # Set curlib before targets compilation
    CURLIB: mylib1
  CrtBndDir:              # Lets create a bind dir
    BNDDIR: BNDHELLO

after:        # optional | Global post-compilation system commands
  DltObj:                 # After all target are build, we delete the bind dir to have a clean slate
    OBJ: BNDHELLO
    OBJTYPE: BndDir

success: {}   # optional | Global on success system commands

failure: {}   # optional | Global on failure system commands

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

    after: {}             # optional | Per-target post-compilation system commands

    success: {}           # optional | Per-target on success system commands

    failure: {}           # optional | Per-target on failure system commands

```

After defining the spec, just call MasterCompiler with unix style parameters

```
java -jar MasterCompiler-1.0-SNAPSHOT.jar -xv -f /home/user/clean_spec.yaml
```

Pretty cool, right? Well, there is more.

## Parameter resolution and validation

Params are enum based, this allows for automatic param conflic resolution and validation in O(1) time along with instant validation at deserialization.

[Params doc](./docs/Params.md)

## CLI

Unix base Cli

* YAML file route:  `-f | --file /route/cool_spec.yaml`
* Debug and verbose mode flags:` -x, -v | -xv`
* Dry run to generate command strings without execution: `--dry-run `
* Diff run to build only what has changed: `--diff`

[Cli doc](./docs/Cli.md) 

## Source migration

MC encourages git usage by automatically migrating source members to ifs stream files and stream files to source members for OPM objects. 

[Migrator doc](./docs/Migration.md)

## Object inspection

Metadata extraction from compiled objects.

[Object Descriptor doc](./docs/Inspection.md) 

## Traceability

Fully transparent and traceble flow of execution and changes.

[Traceability docs](./docs/Traceability.md)

## Contributing

Here is the rule: **You want to reduce complexity, increase readability, and provide functionality.**

Everything we do is a reflection of ourselves; even the world as we know it is a reflection of humanity... but that's another topic.

## Build Master Compiler

clone repo
```
git clone git@github.com:kraudy/MasterCompiler.git
```

compile and run tests
```
mvn clean package
```

That's it.