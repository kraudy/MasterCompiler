# Cli

Master compiler follows unix philosophi in various parts of its design. One of them is the CLI param validation.

All params are validated at the start, so you don't have to insist later on interactive promopts. 

Params format follow the short, long syntax

Short params can be combined

The only required param is the speck path {-f, --file} 

Debug and verbose log output {-x, -v, -xv}

Dry run execution allows to run the compiler without executing any commands, it follows the flow of exceution and generates the command's strings. {--dry-run}

Allows for differentiated build based on last source change compared to object creations using the flag {--diff}

[Argument parser blass](../src/main/java/com/github/kraudy/compiler/ParamMap.ArgParser) 

## Parameters

* YAML file route:  `-f | --file /route/cool_spec.yaml`
* Debug and verbose mode flags:` -x, -v | -xv`
* Dry run to generate command strings without execution: `--dry-run `
* Diff run to build only what has changed: `--diff`
* Omit automatic migration: `--no-migrate`

## Params permutation

Simplest call, just the file path
```bash
java -jar MasterCompiler-1.0-SNAPSHOT.jar -f /home/user/mylib.hello.pgm.rpgle.yaml
# Same as above
java -jar MasterCompiler-1.0-SNAPSHOT.jar --file /home/user/mylib.hello.pgm.rpgle.yaml
```

Add debug flag
```bash
java -jar MasterCompiler-1.0-SNAPSHOT.jar -f /home/user/mylib.hello.pgm.rpgle.yaml -x
# Same as above
java -jar MasterCompiler-1.0-SNAPSHOT.jar -x -f /home/user/mylib.hello.pgm.rpgle.yaml
```

Add verbose flag
```bash
java -jar MasterCompiler-1.0-SNAPSHOT.jar -f /home/user/mylib.hello.pgm.rpgle.yaml -v
```

Add debug verbose flag
```bash
java -jar MasterCompiler-1.0-SNAPSHOT.jar -f /home/user/mylib.hello.pgm.rpgle.yaml -xv
```

Add dry run
```bash
java -jar MasterCompiler-1.0-SNAPSHOT.jar -f /home/user/mylib.hello.pgm.rpgle.yaml --dry-run
```

Add diff build
```bash
java -jar MasterCompiler-1.0-SNAPSHOT.jar -f /home/user/mylib.hello.pgm.rpgle.yaml --diff
```