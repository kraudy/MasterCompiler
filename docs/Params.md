# Params

Params are defined as enums. A command has a defined set of params. So, a command can be defined as a pattern of enums.

This enum based approach allows **MC** to validate every command's param along with other useful functionality 

## Conflict resolution

* If `SRCSTMF` and `SRCFILE` are present, `SRCFILE` is removed to give priority to stream files.
* If `SRCSTMF` is present and `TGTCCSID` is missing, then **MC** adds it.
* If `SRCSTMF` and `EXPORT` are present, `EXPORT` is removed.

## Param validation

* Every command, param, and value is validated during deserialization. 
* Invalid params for a given command are rejected, and an error is raised. 
* Param values are automatically formatted if necessary, e.g., `yes` to `*YES`, `Source` to `*SOURCE`, etc.

## History 

Every change is tracked individually in the history of each param. This gives you full context of what **MC** does.
