# Cli

Master compiler follows unix philosophi in various parts of its design. One of them is the CLI param validation.

* All params are validated at the start, so you don't have to insist later on interactive promopts. 
* Params format follow the short, long syntax
* Short params can be combined

[Argument parser class](../src/main/java/com/github/kraudy/compiler/ArgParser.java) 

## Parameters

* Spec path **or** scan root (one required):
  * YAML file `{-f, --file}`
  * Source root to scan `{--scan}` — see [Scan.md](./Scan.md)
* Base overlay for non-inferable scan params `{--base}` (default: `<scan>/mc-base.yaml` if present)
* Output path for generated YAML `{-o, --output}`
* Default library for scanned targets `{--lib}` (default: `curlib`)
* Generate YAML only, no compile `{--generate-only}` (requires `--scan` and `-o`)
* Debug and verbose log output `{-x, -v, -xv}`
* Dry run execution allows to run the compiler without executing any commands, it follows the flow of exceution and generates the command's strings. `{--dry-run}`
* No migrate flag ommits souce files migration `{--no-migrate}`
* Differentiated build based on last source change compared to object creations `{--diff}`
* Clean deletes created objects after the build `{ -c, --clean }`

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

Add no migrate
```bash
java -jar MasterCompiler-1.0-SNAPSHOT.jar -f /home/user/mylib.hello.pgm.rpgle.yaml --no-migrate
```

## Scan mode (auto YAML)

Generate a topo-sorted spec from a source tree without writing targets by hand:

```bash
java -jar MasterCompiler-1.0-SNAPSHOT.jar --scan /home/user/sources --generate-only -o build.yaml -v
```

Scan and compile in one step:

```bash
java -jar MasterCompiler-1.0-SNAPSHOT.jar --scan /home/user/sources --lib curlib -xv
```

Optional explicit base overlay (otherwise uses `<scan>/mc-base.yaml` when present):

```bash
java -jar MasterCompiler-1.0-SNAPSHOT.jar \
  --scan /home/user/sources --base /home/user/sources/mc-base.yaml -o build.yaml -xv
```

[Scan doc](./Scan.md)