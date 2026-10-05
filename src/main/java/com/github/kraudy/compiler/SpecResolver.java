package com.github.kraudy.compiler;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.kraudy.compiler.CompilationPattern.CompCmd;
import com.github.kraudy.compiler.CompilationPattern.ParamCmd;

/**
 * Dry-resolves each target the same way compile does (MC defaults → inspect →
 * spec defaults → target params → conflict resolution) and writes the filtered
 * result back into {@link BuildSpec.TargetSpec#params} for YAML emission.
 */
public final class SpecResolver {
  private static final Logger logger = LoggerFactory.getLogger(SpecResolver.class);

  private SpecResolver() {}

  public static void resolveAll(BuildSpec spec, ObjectDescriptor descriptor) {
    if (spec == null || spec.targets == null) return;
    for (Map.Entry<TargetKey, BuildSpec.TargetSpec> entry : spec.targets.entrySet()) {
      TargetKey identity = entry.getKey();
      BuildSpec.TargetSpec targetSpec = entry.getValue();
      if (identity == null || targetSpec == null) continue;
      Map<ParamCmd, String> resolved = resolve(
          identity, spec.defaults, targetSpec.params, descriptor);
      targetSpec.params.clear();
      targetSpec.params.putAll(resolved);
    }
  }

  public static Map<ParamCmd, String> resolve(
      TargetKey identity,
      Map<ParamCmd, String> specDefaults,
      Map<ParamCmd, String> targetParams,
      ObjectDescriptor descriptor) {

    TargetKey scratch = new TargetKey(identity.asString());

    /* SRCSTMF may be invalid on the CRT* command (DDS, OPM) but is still the source path */
    if (identity.containsStreamFile()) {
      scratch.setStreamSourceFile(identity.getStreamFile());
    } else if (targetParams != null && targetParams.get(ParamCmd.SRCSTMF) != null) {
      scratch.setStreamSourceFile(targetParams.get(ParamCmd.SRCSTMF));
    }

    if (descriptor != null) {
      try {
        descriptor.getObjectInfo(scratch);
      } catch (Exception e) {
        logger.info("Skipping inspection for {}: {}", scratch.asString(), e.getMessage());
      }
    }

    scratch.putAll(specDefaults);
    scratch.putAll(targetParams);
    scratch.ResolveConflicts();
    return filter(scratch);
  }

  static Map<ParamCmd, String> filter(TargetKey key) {
    Map<ParamCmd, String> out = new LinkedHashMap<ParamCmd, String>();
    boolean hasSrcstmf = key.containsStreamFile();
    List<ParamCmd> pattern = CompilationPattern.getCommandPattern(key.getCompilationCommand());
    for (ParamCmd param : pattern) {
      if (!key.containsKey(param)) continue;
      String value = key.get(param);
      if (value == null || value.isEmpty()) continue;
      if (hasSrcstmf && (param == ParamCmd.SRCFILE || param == ParamCmd.SRCMBR)) {
        continue;
      }
      /* EXPORT(*ALL) uses no binder source */
      if ((param == ParamCmd.SRCFILE || param == ParamCmd.SRCMBR)
          && key.getCompilationCommand() == CompCmd.CRTSRVPGM && key.get(ParamCmd.EXPORT).contains("ALL")) {
        continue;
      }
      if (isIdentity(key, param, value)) continue;
      if (param == ParamCmd.INCDIR) {  // list of quoted directories -> plain list, quoted again on load
        out.put(param, value.replace("'", "").trim());
        continue;
      }
      out.put(param, CommandStringParser.stripClQuotes(value));
    }
    if (hasSrcstmf && !out.containsKey(ParamCmd.SRCSTMF)) {
      String stmf = key.get(ParamCmd.SRCSTMF);
      if (stmf == null || stmf.isEmpty()) stmf = key.getStreamFile();
      if (stmf != null && !stmf.isEmpty()) {
        out.put(ParamCmd.SRCSTMF, CommandStringParser.stripClQuotes(stmf));
      }
    }
    return out;
  }

  static boolean isIdentityParam(TargetKey key, ParamCmd param) {
    CompCmd cmd = key.getCompilationCommand();
    switch (param) {
      case PGM:
        switch (cmd) {
          case CRTBNDRPG:
          case CRTBNDCL:
          case CRTRPGPGM:
          case CRTCLPGM:
            return true;
          default:
            return false;
        }
      case OBJ:
        return cmd == CompCmd.CRTSQLRPGI;
      case FILE:
        switch (cmd) {
          case CRTDSPF:
          case CRTPF:
          case CRTLF:
          case CRTPRTF:
            return true;
          default:
            return false;
        }
      case MODULE:
        switch (cmd) {
          case CRTRPGMOD:
          case CRTCLMOD:
          case CRTSRVPGM:
            return true;
          default:
            return false;
        }
      case SRVPGM:
        return cmd == CompCmd.CRTSRVPGM;
      case CMD:
        return cmd == CompCmd.CRTCMD;
      case BNDDIR:
        return cmd == CompCmd.CRTBNDDIR;
      case DTAARA:
        return cmd == CompCmd.CRTDTAARA;
      case DTAQ:
        return cmd == CompCmd.CRTDTAQ;
      case MSGF:
        return cmd == CompCmd.CRTMSGF;
      default:
        return false;
    }
  }

  static boolean isIdentity(TargetKey key, ParamCmd param, String value) {
    if (!isIdentityParam(key, param)) return false;
    if (param == ParamCmd.MODULE && key.getCompilationCommand() == CompCmd.CRTSRVPGM) {
      if (!isSingleName(value)) return false;
    }
    return namesSameObject(key, value);
  }

  static boolean namesSameObject(TargetKey key, String value) {
    if (value == null) return false;
    String token = firstToken(CommandStringParser.stripClQuotes(value));
    if (token.isEmpty()) return false;

    String lib = null;
    String name = token;
    int slash = token.lastIndexOf('/');
    if (slash >= 0) {
      lib = token.substring(0, slash).replace("*", "");
      name = token.substring(slash + 1);
    }
    if (!name.equalsIgnoreCase(key.getObjectName())) return false;
    if (lib == null || lib.isEmpty()) return true;
    if (lib.equalsIgnoreCase("CURLIB") || lib.equalsIgnoreCase("LIBL")) return true;
    return lib.equalsIgnoreCase(key.getLibrary());
  }

  private static boolean isSingleName(String value) {
    String s = CommandStringParser.stripClQuotes(value).trim();
    if (s.isEmpty()) return false;
    return s.indexOf(' ') < 0;
  }

  private static String firstToken(String value) {
    String s = value.trim();
    int space = s.indexOf(' ');
    return space < 0 ? s : s.substring(0, space);
  }
}
