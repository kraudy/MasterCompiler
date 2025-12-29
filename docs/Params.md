# Params

Params are defined as enums. A command has a defined set of params. So, a command can be defined as a pattern of enums.

This allows MC to validate every command's param along with other useful functionality like conflict resolution between params. e.g., If `SRCSTMF` and `SRCFILE` are present, `SRCFILE` is removed to give priority to stream files and this change is tracked in the history of both params: `SRCSTMF` and `SRCFILE`.

This also permits

* Every command, param, and value is validated during deserialization. 
* Invalid params for a given command are rejected, and an error is raised. 
* Param values are automatically formatted if necessary, e.g., yes to *YES, Source to *SOURCE, etc.