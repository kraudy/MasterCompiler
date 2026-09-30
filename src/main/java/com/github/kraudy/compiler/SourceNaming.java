package com.github.kraudy.compiler;

import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
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
 * are ignored; the object name is the first segment. A TOBi-style description after a
 * dash is ignored too ({@code ART200-Work_with_article.pgm.sqlrpgle} is ART200).
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
    /* NAME.srvpgm.rpgle: a NOMAIN module that is also its own service program (EXPORT(*ALL)) */
    public final boolean ownServiceProgram;

    public ParsedName(String objectName, ObjectType objectType, SourceType sourceType) {
      this(objectName, objectType, sourceType, false);
    }

    public ParsedName(String objectName, ObjectType objectType, SourceType sourceType, boolean ownServiceProgram) {
      this.objectName = objectName;
      this.objectType = objectType;
      this.sourceType = sourceType;
      this.ownServiceProgram = ownServiceProgram;
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

    // Last segment = source type, or a TOBi extension that names the object type too (ARTICLE.pf)
    SourceType sourceType;
    ObjectType objectType = null;
    String last = parts[parts.length - 1].toUpperCase(Locale.ROOT);
    if (TOBI_EXTENSIONS.containsKey(last)) {
      sourceType = TOBI_EXTENSIONS.get(last).sourceType;
      objectType = TOBI_EXTENSIONS.get(last).objectType;
    } else {
      try {
        sourceType = SourceType.fromString(parts[parts.length - 1]);
      } catch (IllegalArgumentException e) {
        return Optional.empty();
      }
    }

    int objectNameEnd = parts.length - 1; // exclusive end index for name parts

    /* NAME.srvpgm.rpgle: build it as a module, the scan adds the service program */
    boolean ownServiceProgram = false;
    if (objectType == null && parts.length >= 3 && "srvpgm".equalsIgnoreCase(parts[parts.length - 2])
        && isValidCombination(sourceType, ObjectType.MODULE)) {
      objectType = ObjectType.MODULE;
      objectNameEnd = parts.length - 2;
      ownServiceProgram = true;
    }

    // Second-to-last may be object type
    if (objectType == null && parts.length >= 3) {
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

    // Object name = first segment (IBM i 10-char limit), without a "-description" suffix
    String objectName = parts[0].toUpperCase(Locale.ROOT);
    int dash = objectName.indexOf('-');
    if (dash > 0) objectName = objectName.substring(0, dash);
    if (!OBJECT_NAME.matcher(objectName).matches()) {
      return Optional.empty();
    }

    // Descriptive middles (parts[1 .. objectNameEnd)) are intentionally ignored
    return Optional.of(new ParsedName(objectName, objectType, sourceType, ownServiceProgram));
  }

  /* Extensions TOBi / Code for IBM i use that imply the object type */
  private static final Map<String, ParsedName> TOBI_EXTENSIONS = new HashMap<String, ParsedName>();
  static {
    tobi("PF", ObjectType.PF, SourceType.DDS);
    tobi("LF", ObjectType.LF, SourceType.DDS);
    tobi("DSPF", ObjectType.DSPF, SourceType.DDS);
    tobi("PRTF", ObjectType.PRTF, SourceType.DDS);
    tobi("TABLE", ObjectType.TABLE, SourceType.SQL);
    tobi("VIEW", ObjectType.VIEW, SourceType.SQL);
    tobi("INDEX", ObjectType.INDEX, SourceType.SQL);
    tobi("SQLPRC", ObjectType.PROCEDURE, SourceType.SQL);
    tobi("SQLUDF", ObjectType.FUNCTION, SourceType.SQL);
    tobi("SQLTRG", ObjectType.TRIGGER, SourceType.SQL);
    tobi("SQLSEQ", ObjectType.SEQUENCE, SourceType.SQL);
  }

  private static void tobi(String extension, ObjectType objectType, SourceType sourceType) {
    TOBI_EXTENSIONS.put(extension, new ParsedName(extension, objectType, sourceType));
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
