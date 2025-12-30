# Inspection

A compiled object stores metadata. This metadata is useful if you want to know how an object was compiled. The legacy way of doing this was calling system APIs like **QBNLPGMI** or **QBNRMODI**, which can be a little like hitting your head against the wall since you need to deal with raw hex data and not much documentation about it is done.

Another way of doing that is using the new DB2-provided services, and this is what **MC** does. 

It searches for the compilation target's object to extract its metadata and add the params to the compilation command. These extracted params are a suggestion; they are overridden by the Spec params if provided.

[Object Descriptor class](../src/main/java/com/github/kraudy/compiler/ObjectDescriptor.java) is the one that does the inspection. It works well for pgm, srvpgm and modules. It still needs some love for sql and dds objects.

Services used

* **PROGRAM_INFO** for Pgm and SrvPgm objects
* **BOUND_MODULE_INFO** for Module objects
* **COMMAND_INFO** for Command objects
