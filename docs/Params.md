# Params

Params are defined as enums. A command has a defined set of params. So, a command can be defined as a pattern of enums.

This enum based approach allows **MC** to validate every command's param along with other useful functionality 

## Conflict resolution

Resolution runs on every compile command string (not only under `-x`).

* If `SRCSTMF` and `SRCFILE` are present, `SRCFILE` is removed to give priority to stream files.
* If `SRCSTMF` is present and `TGTCCSID` is missing, then **MC** adds it.
* If `SRCSTMF` and `EXPORT` are present, `EXPORT` is removed.

## Param validation

* Every command, param, and value is validated during deserialization. 
* Invalid params for a given command are rejected, and an error is raised. 
* Param values are automatically formatted if necessary, e.g., `yes` to `*YES`, `Source` to `*SOURCE`, etc.

## History 

Every change is tracked individually in the history of each param. This gives you full context of what **MC** does.

## Precedence

At compile time and when generating a spec:

1. **MC defaults** (`Utilities.SetDefaultParams`)
2. **Object inspection** (existing object metadata, if any)
3. Spec **`defaults:`**
4. Target **`command:`** tokens (if present; parsed into params)
5. Target **`params:`** — **wins**

A raw command string is never executed. See [Spec.md](./Spec.md#full-compile-command-command).
