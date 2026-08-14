package com.github.kraudy.compiler;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import com.github.kraudy.compiler.CompilationPattern.ObjectType;
import com.github.kraudy.compiler.CompilationPattern.SourceType;

/**
 * Parses source filenames into target identity using the MC naming contract:
 * <pre>
 *   {objectName}.{objectType}.{sourceType}   preferred
 *   {objectName}.{sourceType}                objectType defaulted
 * </pre>
 * Extra descriptive middle tokens (e.g. {@code hello2.nomain.module.rpgle})
 * are ignored; the object name is the first segment.
 */
public final class SourceNaming {

  private static final Pattern OBJECT_NAME = Pattern.compile("[A-Z0-9$#@_]{1,10}");

  private SourceNaming() {}

  /**
   * Result of parsing a source file name (not the full path).
   */
  public static final class ParsedName {
    public final String objectName;
    public final ObjectType objectType;
    public final SourceType sourceType;

    public ParsedName(String objectName, ObjectType objectType, SourceType sourceType) {
      this.objectName = objectName;
      this.objectType = objectType;
      this.sourceType = sourceType;
    }

    public String toTargetKey(String library) {
      return library + "." + objectName + "." + objectType.name() + "." + sourceType.name();
    }
  }

  /**
   * @param fileName basename only (e.g. {@code FAM300.module.RPGLE})
   * @return empty if the file is not a recognized MC source
   */
  public static Optional<ParsedName> parseFileName(String fileName) {
    if (fileName == null || fileName.isEmpty()) {
      return Optional.empty();
    }

    // Strip directory if a path was passed by mistake
    String base = fileName;
    int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
    if (slash >= 0) {
      base = base.substring(slash + 1);
    }
    if (base.isEmpty()) {
      return Optional.empty();
    }

    String[] parts = base.split("\\.");
    if (parts.length < 2) {
      return Optional.empty();
    }

    /* Copy/include members (Copy_Mbrs/FOO.include.RPGLE), not compile targets */
    for (int i = 1; i < parts.length - 1; i++) {
      if ("include".equalsIgnoreCase(parts[i])) {
        return Optional.empty();
      }
    }

    // Last segment = source type
    SourceType sourceType;
    try {
      sourceType = SourceType.fromString(parts[parts.length - 1]);
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }

    ObjectType objectType = null;
    int objectNameEnd = parts.length - 1; // exclusive end index for name parts

    // Second-to-last may be object type
    if (parts.length >= 3) {
      try {
        ObjectType candidate = ObjectType.valueOf(parts[parts.length - 2].toUpperCase(Locale.ROOT));
        if (isValidCombination(sourceType, candidate)) {
          objectType = candidate;
          objectNameEnd = parts.length - 2;
        }
      } catch (IllegalArgumentException ignored) {
        // not an object type — fall through to default
      }
    }

    if (objectType == null) {
      objectType = defaultObjectType(sourceType);
      if (objectType == null) {
        return Optional.empty();
      }
      if (!isValidCombination(sourceType, objectType)) {
        return Optional.empty();
      }
    }

    // Object name = first segment (IBM i 10-char limit)
    String objectName = parts[0].toUpperCase(Locale.ROOT);
    if (!OBJECT_NAME.matcher(objectName).matches()) {
      return Optional.empty();
    }

    // Descriptive middles (parts[1 .. objectNameEnd)) are intentionally ignored
    return Optional.of(new ParsedName(objectName, objectType, sourceType));
  }

  /**
   * Default object type when the filename omits it (e.g. {@code ADDNUM.RPGLE} → PGM).
   */
  public static ObjectType defaultObjectType(SourceType sourceType) {
    switch (sourceType) {
      case RPG:
      case RPGLE:
      case SQLRPGLE:
      case CLP:
      case CLLE:
        return ObjectType.PGM;
      case BND:
        return ObjectType.SRVPGM;
      case CMD:
        return ObjectType.CMD;
      case MNU:
        return ObjectType.MNU;
      case QMQRY:
        return ObjectType.QMQRY;
      case BNDDIR:
        return ObjectType.BNDDIR;
      case DTAARA:
        return ObjectType.DTAARA;
      case DTAQ:
        return ObjectType.DTAQ;
      case MSGF:
        return ObjectType.MSGF;
      case DDS:
      case SQL:
        // object type required in the name (pf.dds, table.sql, ...)
        return null;
      default:
        return null;
    }
  }

  public static boolean isValidCombination(SourceType sourceType, ObjectType objectType) {
    try {
      return CompilationPattern.getCompilationCommand(sourceType, objectType) != null;
    } catch (Exception e) {
      return false;
    }
  }
}
