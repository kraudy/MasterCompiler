
<div style="text-align: center;">
  <img src="./images/Master%20Compiler.jpg" alt="alt text" style="width: 20%; max-width: 800px; height: auto;">
</div>

# MasterCompiler

Master compilation tool for the IBM i platform. 

YAML-based, sensible, open-source, modern, clean, and elegant approach to automate and streamline object compilation.

Do you like building cool stuff for fun and freedom? Then this project is for you.

---

This project aims to give the whole IBM i community a standard way to describe the compilation flow of objects in a simple, elegant, modern, and clean manner. Affords easy integration with any DevOps pipeline; could be run locally (just upload the JAR file) or remotely in a Docker container.

## Requirementes

Java 8

---

## Compilation target?

To compile an object, it must be defined as a unique key of the form **library.name.objectType.sourceType**. The **objectType** and **sourceType** of the target key define the compilation command to be executed.

* mylib.hello.pgm.rpgle         => CRTBNDRPG
* mylib.sqlhello.pgm.sqlrpgle   => CRTSQLRPGI
* mylib.dsphello.dspf.dds       => CRTDSPF
* mylib.tabhello.table.sql      => RUNSQLSTM
* mylib.modhello.module.rpgle   => CRTRPGMOD
* mylib.srvhello.srvpgm.bnd     => CRTSRVPGM

[Object types](./src/main/java/com/github/kraudy/compiler/CompilationPattern.java#L133) 

[Sourece types](./src/main/java/com/github/kraudy/compiler/CompilationPattern.java#L69) 

[Compilation command from Object type and Sourece types](./src/main/java/com/github/kraudy/compiler/CompilationPattern.java#L239) 

[Param pattern from Compilation command](./src/main/java/com/github/kraudy/compiler/CompilationPattern.java#L1064) 

---

## Flow of compilation?

Define a yaml file

[Full spec here](./src/main/java/com/github/kraudy/compiler/BuildSpec.java) 
[Hooks deserializer](./src/main/java/com/github/kraudy/compiler/CommandMapDeserializer.java) 
[Compilation params deserializer](./src/main/java/com/github/kraudy/compiler/ParamMapDeserializer.java) 
[More yaml examples]() 

```yaml
defaults: {}  # optional | Global compilation command params

before:       # optional | Global pre-compilation system commands
  chglibl:                # cl command
    libl: mylib1 mylib2   # cl command param

after: {}     # optional | Global post-compilation system commands

success: {}   # optional | Global on success system commands

failure: {}   # optional | Global on success system commands

targets:      # Required | Global on success system commands

  mylib1.hello.pgm.rgple:  # Required | Compilation target

    params:               # Required | Per-target compilation command params
      # Compilation params
      SRCSTMF: /home/sources/HELLO.RPGLE
      DFTACTGRP: no
      ACTGRP: QILE
      STGMDL: Snglvl      # note how params names are flexible
      OPTION: EVENTF
      DBGVIEW: source
      REPLACE: yes
      USRPRF: user
      TGTRLS: V7R5M0
      PRFDTA: nocol

    before: {}            # optional | Per-target pre-compilation system commands

    after: {}             # optional | Per-target post-compilation system commands

    success: {}           # optional | Per-target on success system commands

    failure: {}           # optional | Per-target on success system commands

  # You can define more targets, each one with its own hooks and params
  mylib2.sqlhello.pgm.sqlrpgle: 

    #params:                no params, will use defaults

    before: 
      chgcurlib:          # change cur lib before target compilation
        CURLIB: mylib2
    
    # ignore other hooks

  # A third target with no params and no hooks. Simplest case
  mylib2.modhello.module.rpgle: {} 

```

Then just call MasterCompiler like this: **java -jar MasterCompiler-1.0-SNAPSHOT.jar -xv -f /home/user/clean_spect.yaml**

Pretty cool, right? Well, here is more:


## Automatic migration

MC encourages this by automatically migrating source members to ifs stream files and stream files to source members for OPM objects. 

[Migrator](./src/main/java/com/github/kraudy/compiler/Migrator.java) 


## Automatic param inspection

MC tries to extract compilation params from existing objects.

[ObjectDescriptor](./src/main/java/com/github/kraudy/compiler/ObjectDescriptor.java) 


## Automatic param resolution

Conflicts between command params are automatically resolved.

[Resolve conflict](./src/main/java/com/github/kraudy/compiler/ParamMap.java#L153) 

## Automatic param validation

If an invalid param is provided for a command, an exception is thrown.

[Reject invalid param](./src/main/java/com/github/kraudy/compiler/ParamMap.java#L91) 

## Unix style CLI

* YAML file route:  `-f | --file /route/cool_spec.yaml`
* Debug and verbose mode flags:` -x, -v | -xv`
* Dry run to generate command strings without execution: `--dry-run `
* Diff run to build only what has changed: `--diff`

[Argument parser](./src/main/java/com/github/kraudy/compiler/ParamMap.ArgParser) 

## Traceability

Fully transparent and traceble flow of execution and changes.

* All messages are logs 
* Exceptions are handled and raised. No context loss. 
* Each param change is tracked individually
* Each command executed is tracked in a chain of commands

[Argument parser](./src/main/java/com/github/kraudy/compiler/ParamMap.ArgParser) 
