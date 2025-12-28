
<img src="./images/Master%20Compiler.jpg" alt="Master Compiler logo" style="display: block; margin: 0 auto; width: 40%; max-width: 800px; height: auto; object-fit: cover; aspect-ratio: 1 / 1;">

# Master Compiler

Master compilation tool for the IBM i platform. 

YAML-based, sensible, open-source, modern, clean, and elegant approach to automate and streamline object compilation.

Do you like building cool stuff for fun and freedom? Then this project is for you.

---

This project aims to give the whole IBM i community a standard way to describe the compilation flow of objects and allow easy integration with any DevOps pipeline to be run locally (just upload the JAR file) or remotely in a Docker container.

Requirements? Java 8.

---

## Compilation target?

To compile an object, it must be defined as a unique key of the form **library.name.objectType.sourceType**. 

The **objectType** and **sourceType** part of the target key define the compilation command to be executed.

[Target keys doc](./docs/TargetKey.md)

[Compilation Patterns](./src/main/java/com/github/kraudy/compiler/CompilationPattern.java#L1) 

---

## Flow of compilation?

Compiling an object often requires other CL commands to be performed on the same job to set up the appropriate environment: Library list, files overrides, binding directories, etc.

We can define all these patterns in a Yaml file.

[Specs doc](./docs/Spec.md)

[Full spec here](./src/main/java/com/github/kraudy/compiler/BuildSpec.java) 

[Hooks deserializer](./src/main/java/com/github/kraudy/compiler/CommandMapDeserializer.java) 

[Compilation params deserializer](./src/main/java/com/github/kraudy/compiler/ParamMapDeserializer.java) 

[More yaml examples]() 

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

  # Ile rpgle with binding module
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
      STGMDL: Snglvl      # note how params names are flexible
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

## Source migration

MC encourages git usage by automatically migrating source members to ifs stream files and stream files to source members for OPM objects. 

[Migrator](./src/main/java/com/github/kraudy/compiler/Migrator.java) 

## Object inspection

MC tries to extract compilation params from existing objects.

[ObjectDescriptor](./src/main/java/com/github/kraudy/compiler/ObjectDescriptor.java) 

## Parameter resolution

Conflicts between command params are automatically resolved. e.g., If `SRCSTMF` and `SRCFILE` are present, `SRCFILE` is removed to give priority to stream files.

[Resolve conflict](./src/main/java/com/github/kraudy/compiler/ParamMap.java#L153) 

## Parameter validation

* Every command, param, and value is validated during deserialization. 
* Invalid params for a given command are rejected, and an error is raised. 
* Param values are automatically formatted if necessary, e.g., yes to *YES, Source to *SOURCE, etc.

[Reject invalid param](./src/main/java/com/github/kraudy/compiler/ParamMap.java#L91) 

## Unix style CLI

* YAML file route:  `-f | --file /route/cool_spec.yaml`
* Debug and verbose mode flags:` -x, -v | -xv`
* Dry run to generate command strings without execution: `--dry-run `
* Diff run to build only what has changed: `--diff`

[Argument parser](./src/main/java/com/github/kraudy/compiler/ParamMap.ArgParser) 

## Traceability

Fully transparent and traceble flow of execution and changes.

* All messages and joblog entries are loged 
* Failed compilation spools are loged.
* Exceptions are handled and raised. No context loss with full stack trace.
* Fail early and loud.
* Each param change is tracked individually with its own history.
* Each command executed is tracked in a chain of commands

[]() 

## Contributing

"Follow the spirit of the code" What does that mean? If you go through the lines of a well-thought-out code (or any piece of art), you can actually get a glimpse of the character and philosophy of its creators and their goal.

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