# Errors

MC implements a custom Exception [CompilerException](../src/main/java/com/github/kraudy/compiler/CompilerException.java) that allows for a full stack trace without loss of context when an error occurs

* All messages and joblog entries are loged 
* Failed compilation spools are loged.
* Exceptions are handled and raised. No context loss with full stack trace.
* Fail early and loud.
* Each param change is tracked individually with its own history.
* Each command executed is tracked in a chain of commands
