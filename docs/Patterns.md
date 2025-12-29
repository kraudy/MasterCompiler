# Patterns

It turns out that this endeavor is actually quite complex. So, how to deal with complexity?

The most important thing in programming is data structures. If you have the right data structures, the algorithm will naturally follow and the code will be clean. The same applies to databases (which are also data structures).

If you don't do this, this thing called Turing completeness complexity will propagate to your code and will be a nightmare. You should also try to avoid fancy or complex algorithms if not necessary.

Now, the backbone of Master Compiler to deal with complexity is the param to map enum-based data structures that you can find in [Compilation Pattern Class](../src/main/java/com/github/kraudy/compiler/CompilationPattern.java)

This is based on the simple observation that if you prompt each command on the ibm i, you get a list of available params for that command. No other operating system does that, so the obvious step would be to take advantage of it, and that's what MC does.

Each command defines a pattern of params, you only need to map each command to its pattern. This allows us to have a ground truth to test every command's params and enforce strong typing, which, ironically, allows commands and params names to be flexible in the YAML spec. 

On top of [Compilation Pattern Class](../src/main/java/com/github/kraudy/compiler/CompilationPattern.java), the classes [ParamMap](../src/main/java/com/github/kraudy/compiler/ParamMap.java), [ParamValue](../src/main/java/com/github/kraudy/compiler/ParamValue.java), and [TargetKey](../src/main/java/com/github/kraudy/compiler/TargetKey.java) are build. They define the core structure of the tool. The classes around them complement them. You see how everything bubbles up from the data structures?
