package com.github.kraudy.compiler;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.github.kraudy.compiler.CompilationPattern.ParamCmd;


/*
 * Defines all the commands and params as enums.
 * Uses these enums to describe all commands as patterns of params
 * Maps commands to patterns
 * Maps source type to compilaiton commands
 * 
 * To include a new command: 
 *  - Add the command enum to SysCmd, ExecCmd or CompCmd accordingly.
 *  - Create a new list with the command pattern
 *  - Add the new command and its pattern to the commandToPatternMap data structure
 */
public class CompilationPattern {

  public interface Command {
    String name();  // Mirrors Enum.name() for consistency
  }

  // TODO: Add functionality to call programs.
  //public enum ExecCmd implements Command { 
  //  CALL,
  //} 

  public enum SysCmd implements Command { 
    // Library commands
    CHGLIBL, CHGCURLIB, 
    // Dependency commands
    DSPPGMREF, DSPOBJD, DSPDBR ,
    // Bind dir related commands
    ADDBNDDIRE,
    // Overs
    OVRDBF, OVRPRTF, DLTOVR,
    //
    CHGOBJD, 
    // Source pf
    CRTSRCPF, ADDPFM,
    // Migration
    CPYFRMSTMF, CPYTOSTMF,
    // Objects
    DLTOBJ, CRTDUPOBJ,
    // Stream files
    CHGCURDIR, RMVDIR,
    // PASE
    QSH,

    // Messages
    ADDMSGD, 

    // Triggers
    //ADDPFTRG

    // Pgms
    CALL,

    ;

    public static SysCmd fromString(String value) {
      try {
        return SysCmd.valueOf(value.toUpperCase().trim());
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("Could not get system command from string: '" + value + "'");
      }
    }

  }

  public enum CompCmd implements Command { 
    CRTRPGMOD, CRTSQLRPGI, CRTBNDRPG, CRTRPGPGM, CRTCLMOD, CRTBNDCL, CRTCLPGM, RUNSQLSTM, 
    CRTSRVPGM, CRTDSPF, CRTLF, CRTPRTF, CRTMNU, CRTQMQRY, CRTPF, CRTCMD,
    CRTBNDDIR, CRTDTAARA, CRTDTAQ, CRTMSGF
    ;
  }

  public static final  List<SourceType> IleSources = Arrays.asList(
    SourceType.RPG, SourceType.CLP, SourceType.DDS
  );

  public enum SourceType { 
    RPG, RPGLE, SQLRPGLE, CLP, CLLE, SQL, BND, DDS,
    CMD, MNU, QMQRY, BNDDIR, DTAARA, DTAQ, MSGF
    ;

    public static SourceType fromString(String value) {
      try {
        return SourceType.valueOf(value.toUpperCase().trim());
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("Could not get source type from object attribute '" + value + "'");
      }
    } 

    /* Returns default source phisical file based on source type and object type */
    public static String defaultSourcePf (SourceType sourceType, ObjectType objectType){
      switch (sourceType){
        case RPG:
          return DftSrc.QRPGSRC.name();
        case RPGLE:
          return DftSrc.QRPGLESRC.name();
        case SQLRPGLE:
          return DftSrc.QSQLRPGSRC.name();
        case BND:
          return DftSrc.QSRVSRC.name(); 
        case CLP:
        case CLLE:
          return DftSrc.QCLSRC.name();
        case CMD:
          return DftSrc.QCMDSRC.name();
        case BNDDIR:
          return DftSrc.QBNDSRC.name();
        case DTAARA:
          return DftSrc.QDTAARA.name();
        case DTAQ:
          return DftSrc.QDTAQSRC.name();
        case MSGF:
          return DftSrc.QMSGFSRC.name();
        case DDS:
          switch (objectType) {
            case DSPF:
              return DftSrc.QDSPFSRC.name();
            case PF:
              return DftSrc.QPFSRC.name();
            case LF:
              return DftSrc.QLFSRC.name();
            case PRTF:
              return  DftSrc.QPRTFSRC.name();
          }
          
        case SQL:
          return DftSrc.QSQLSRC.name();
        default:
          throw new IllegalArgumentException("Could not get default sourcePf for '" + sourceType + "'");
      }
    }
  }

  /* Compiled objects types */
  public enum ObjectType { 
    PGM, SRVPGM, MODULE, TABLE, LF, INDEX, VIEW, ALIAS, PROCEDURE, FUNCTION, TRIGGER, SEQUENCE, PF, DSPF, PRTF,
    CMD, MNU, QMQRY, DTAARA, DTAQ, BNDDIR, MSGF,
    ;
    public String toParam(){
      return "*" + this.name();
    }
  } 

  /* Default source files */
  public enum DftSrc { 
    QRPGLESRC, QRPGSRC, QCLSRC, QSQLSRC, QSRVSRC, QDSPFSRC, QPFSRC, QLFSRC, QSQLRPGSRC, QSQLMODSRC, QPRTFSRC,
    QCMDSRC, QBNDSRC, QDTAARA, QDTAQSRC, QMSGFSRC
  }

  /* Commands params as enums */
  public enum ParamCmd { 
    PGM, MODULE, OBJ, OBJTYPE, OUTPUT, OUTMBR, SRVPGM, BNDSRVPGM, LIBL, SRCFILE, SRCMBR, ACTGRP, DFTACTGRP, BNDDIR, COMMIT, TEXT, TGTCCSID, CRTFRMSTMF,
    OPTION, TGTRLS, SRCSTMF,
    // RPG/CL
    GENLVL, DBGVIEW, DBGENCKEY, OPTIMIZE, INDENT, CVTOPT, SRTSEQ, LANGID, REPLACE, USRPRF, AUT, TRUNCNBR, FIXNBR, ALWNULL, DEFINE, ENBPFRCOL, PRFDTA, 
    LICOPT, INCDIR, PGMINFO, INFOSTMF, PPGENOPT, PPSRCFILE, PPSRCMBR, PPSRCSTMF, REQPREXP, PPMINOUTLN,
    GENOPT, SAAFLAG, PRTFILE, PHSTRC, ITDUMP, SNPDUMP, CODELIST, IGNDECERR, LOG, ALWRTVSRC, INCFILE, STGMDL,

    // SQLRPGLEI
    RDB, RPGPPOPT, ALWCPYDTA, CLOSQLCSR, ALWBLK, DLYPRP, CONACC, DATFMT, DATSEP, TIMFMT, TIMSEP, RDBCNNMTH, DFTRDBCOL, DYNDFTCOL, SQLPKG, SQLPATH, SQLCURR,
    FLAGSTD, DYNUSRPRF, CVTCCSID, TOSRCFILE, DECRESULT, DECFLTRND, COMPILEOPT,
    
    // RUNSQLSTM
    NAMING, ERRLVL, MARGINS, DECMPT, PROCESS, SECLVLTXT, SQLCURRULE, SYSTIME,

    // CRTSRVPGM
    EXPORT, DETAIL, ALWUPD, ALWLIBUPD, ALWRINZ, ARGOPT, IPA, IPACTLFILE,

    // CRTDSPF
    FILE, FLAG, DEV, MAXDEV, ENHDSP, RSTDSP, DFRWRT, CHRID, DECFMT, SFLENDTXT, WAITFILE, WAITRCD, DTAQ, SHARE, LVLCHK,

    // CRTPF
    RCDLEN, FILETYPE, MBR, SYSTEM, EXPDATE, MAXMBRS, ACCPTHSIZ, PAGESIZE, MAINT, RECOVER, FRCACCPTH, SIZE, ALLOCATE, CONTIG, UNIT, FRCRATIO,
    DLTPCT, REUSEDLT, CCSID, ALWDLT, NODGRP, PTNKEY,

    // CRTLF
    DTAMBRS, FMTSLR,

    // CHGLIBL, CURLIB
    CURLIB,

    // CRTPRTF
    DEVTYPE, LPI, CPI, FRONTMGN, BACKMGN, OVRFLW, FOLD, RPLUNPRT, ALIGN, CTLCHAR, CHLVAL, FIDELITY, PRTQLTY, FORMFEED, DRAWER, OUTBIN, FONT,     
    FNTCHRSET, CDEFNT, TBLREFCHR, PAGDFN, FORMDF, AFPCHARS, PAGRTT, MULTIUP, REDUCE, PRTTXT, JUSTIFY, DUPLEX, UOM, FRONTOVL, BACKOVL, CVTLINDTA, 
    IPDSPASTHR, USRRSCLIBL, CORNERSTPL, EDGESTITCH, SADLSTITCH, FNTRSL, SPOOL, OUTQ, FORMTYPE, COPIES, DAYS, PAGERANGE, MAXRCDS, FILESEP, SCHEDULE,
    HOLD, SAVE, OUTPTY, USRDTA, SPLFOWN, USRDFNOPT, USRDFNDTA, USRDFNOBJ, TOSTMF, WSCST,

    // CRTCMD
    CMD, REXSRCFILE, REXSRCMBR, REXCMDENV, REXEXITPGM, THDSAFE, MLTTHDACN, VLDCKR, ALLOW, ALWLMTUSR, MAXPOS, PMTFILE,  
    HLPSHELF, HLPPNLGRP, HLPID, HLPSCHIDX, PMTOVRPGM, ENBGUI,

    // CRTMNU
    MENU, TYPE, DSPF, MSGF, CMDLIN, DSPKEY, PRDLIB,

    // OVRDBF
    TOFILE, POSITION, RCDFMTLCK, NBRRCDS, EOFDLY, EXPCHK, INHWRT, SECURE, OVRSCOPE, OPNSCOPE, SEQONLY, DSTDTA, LVL,

    // OVRPRTF
    SPLFNAME, IGCDTA, IGCEXNCHR, IGCCHRRTT, IGCCPI, IGCSOSI, IGCCDEFNT,

    // CRTQMQRY
    QMQRY,

    // CRTSRCPF
    ACCPTH,

    // ADDPFM
    SRCTYPE,

    // CPYFRMSTMF
    FROMSTMF, TOMBR, MBROPT, CVTDTA, TABEXPN, STMFCODPAG,

    // CPYTOSTMF
    FROMMBR, STMFOPT, DBFCCSID, STMFCCSID, ENDLINFMT,

    // DLTOBJ
    ASPDEV, RMVMSG,

    // CRTDUPOBJ
    FROMLIB, TOLIB, NEWOBJ, TOASPDEV,

    // CHGCURDIR
    DIR, 

    // RMVDIR
    SUBTREE, RMVLNK, 

    // CRTDTAARA
    DTAARA, LEN, VALUE, RMTDTAARA, RMTLOCNAME, LCLLOCNAME, MODE, RMTNETID,

    // CRTDTAQ
    MAXLEN, FORCE, SEQ, SENDERID, AUTORCL,

    // ADDMSGD
    MSGID, MSG, SECLVL, SEV, FMT, VALUES, SPCVAL, RANGE, REL, DFT, DFTPGM, DMPLST, ALROPT, LOGPRB,

    // CALL
    PARM,

    ;

    /* Convert string to param enum */
    public static ParamCmd fromString(String value) {
      try {
        return ParamCmd.valueOf(value.toUpperCase().trim());
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("Could not get compilation command param from string: '" + value + "'");
      }
    }

    /* Convert param enum to string */
    public String paramString(String val){
      return " " + this.name() + "(" + val + ")";
    }
    
  }

  /* Execution error messages */
  public enum ErrMsg { 
    CPF2112,  //Object type *BNDDIR already exists.
    CPF5D10,  //Not able to insert binding directory into library.
    CPF5D0B,  //Binding directory was not created

    RNS9380,  // CCSID 1208 of stream file not valid for TGTCCSID(*SRC)
    RNS9339,  // Could not open stream file
    ; 

    public static ErrMsg fromString(String value) {
      try {
          return ErrMsg.valueOf(value.toUpperCase().trim());
      } catch (IllegalArgumentException e) {
          throw new IllegalArgumentException("Could not convert '" + value + "' to ErrMsg. Unknown value: '" + value + "'");
      }
    }
  }

  /* Params defined values. You see these when you press F4 */
  public enum ValCmd { 
    FIRST, REPLACE, OUTFILE, LIBL, FILE, DTAARA, DTAQ, PGM, MODULE, OBJ, SRVPGM, CURLIB, ALL, CURRENT,
    NONE, BASIC, FULL, LSTDBG, JOB, EVENTF, NOEVENTF,

    YES, NO, STMT, SOURCE, LIST, HEX, JOBRUN, USER, LIBCRTAUT, PEP, NOCOL, PRINT, SNGLVL,
    
    // OVRDBF
    ACTGRPDFN, CALLLVL,

    CHG ,CS, RR ,UR ,RS ,NC,  

    // Object types as F4 values
    BNDDIR, ALRTBL, AUTL, CFGL ,CHTFMT, CLD , CLS , CMD , CNNL, COSD, CRQD,  
    CSI, CSPMAP,
    CSPTBL, CTLD, DEVD, DTADCT , EDTD, FCT, FNTRSC, FNTTBL, FORMDF, FTR,
    GSS, IGCDCT, IGCSRT, IGCTBL, IMGCLG, IPXD, JOBD, JOBQ, JRN, JRNRCV, LIB, LIND, LOCALE,
    MEDDFN, MENU, MGTCOL, MODD, MSGF, MSGQ, NODGRP, NODL, NTBD, NWID, NWSCFG, NWSD,
    OUTQ, OVL, PAGDFN, PAGSEG, PDFMAP, PDG, PNLGRP, PSFCFG, QMFORM, QMQRY , QRYDFN, SBSD,
    SCHIDX, SPADCT, SQLPKG, SQLUDT, SQLXSR, SSND, TBL, TIMZON, USRIDX, USRQ, USRSPC, VLDL,
    WSCST,

    //CVTOPT
    DATETIME, VARCHAR , GRAPHIC, PRV, 

    // CLOSQLCSR
    ENDACTGRP, ENDMOD, CALLER,

    // CRTDTAARA
    DEC,

    // CRTDTAQ
    MAX16MB, MAX2GB, 

    // ADDMSGD
    CHAR,

    // CRTCMD
    BATCH, INTERACT, BPGM, IPGM, BREXX, IREXX, EXEC, BMOD, IMOD, COMMAND, CPICOMM, EXECSQL,

    // CRTPRTF
    SCS, IPDS, LINE, AFPDSLINE, USERASCII, AFPDS, FRONTMGN, CONTENT, ABSOLUTE, STD, DRAFT, NLQ, FASTDRAFT, FNTCHRSET, INCH, CM, 
    FRONTOVL, FILEEND, IMMED, JOBEND, CURUSRPRF, CURGRPPRF, JOBGRPPRF, FCFC, MACHINE,  

    ; 

    public static ValCmd fromString(String value) {
      try {
          return ValCmd.valueOf(value.toUpperCase().trim().replace("*", "")); // Remove the leading "*" and convert to enum
      } catch (IllegalArgumentException e) {
          throw new IllegalArgumentException("Could not convert '" + value + "' to ValCmd. Unknown value: '" + value + "'");
      }
    }

    @Override
    public String toString() {
        return "*" + name();
    }  
  }

  /*
   * Maps Source Type => Object Type => Compilation command 
   */
  private static final Map<SourceType, Map<ObjectType, CompCmd>> typeToCmdMap = new EnumMap<>(SourceType.class);

  static{

    /* Source type: BND */
    Map<ObjectType, CompCmd> bndMap = new EnumMap<>(ObjectType.class);
    bndMap.put(ObjectType.SRVPGM, CompCmd.CRTSRVPGM);
    typeToCmdMap.put(SourceType.BND, bndMap);

    /* Source type: RPG */
    Map<ObjectType, CompCmd> rpgMap = new EnumMap<>(ObjectType.class);
    rpgMap.put(ObjectType.PGM, CompCmd.CRTRPGPGM);
    typeToCmdMap.put(SourceType.RPG, rpgMap);

    /* Source type: RPGLE */
    Map<ObjectType, CompCmd> rpgLeMap = new EnumMap<>(ObjectType.class);
    rpgLeMap.put(ObjectType.MODULE, CompCmd.CRTRPGMOD);
    rpgLeMap.put(ObjectType.PGM, CompCmd.CRTBNDRPG);
    typeToCmdMap.put(SourceType.RPGLE, rpgLeMap);

    /* Source type: SQLRPGLE */
    Map<ObjectType, CompCmd> sqlRpgLeMap = new EnumMap<>(ObjectType.class);
    sqlRpgLeMap.put(ObjectType.MODULE, CompCmd.CRTSQLRPGI);
    sqlRpgLeMap.put(ObjectType.PGM, CompCmd.CRTSQLRPGI);
    typeToCmdMap.put(SourceType.SQLRPGLE, sqlRpgLeMap);

    /* Source type: CLP */
    Map<ObjectType, CompCmd> clpMap = new EnumMap<>(ObjectType.class);
    clpMap.put(ObjectType.PGM, CompCmd.CRTCLPGM);
    typeToCmdMap.put(SourceType.CLP, clpMap);

    /* Source type: CLLE */
    Map<ObjectType, CompCmd> clleMap = new EnumMap<>(ObjectType.class);
    clleMap.put(ObjectType.MODULE, CompCmd.CRTCLMOD);
    clleMap.put(ObjectType.PGM, CompCmd.CRTBNDCL);
    typeToCmdMap.put(SourceType.CLLE, clleMap);

    /* Source type: SQL */
    Map<ObjectType, CompCmd> sqlMap = new EnumMap<>(ObjectType.class);
    sqlMap.put(ObjectType.TABLE, CompCmd.RUNSQLSTM);
    sqlMap.put(ObjectType.INDEX, CompCmd.RUNSQLSTM);
    sqlMap.put(ObjectType.VIEW, CompCmd.RUNSQLSTM);
    sqlMap.put(ObjectType.ALIAS, CompCmd.RUNSQLSTM);
    sqlMap.put(ObjectType.PROCEDURE, CompCmd.RUNSQLSTM);
    sqlMap.put(ObjectType.FUNCTION, CompCmd.RUNSQLSTM);
    sqlMap.put(ObjectType.TRIGGER, CompCmd.RUNSQLSTM);
    sqlMap.put(ObjectType.SEQUENCE, CompCmd.RUNSQLSTM);
    typeToCmdMap.put(SourceType.SQL, sqlMap);

    /* Source type: DDS */
    Map<ObjectType, CompCmd> ddsMap = new EnumMap<>(ObjectType.class);
    ddsMap.put(ObjectType.PF, CompCmd.CRTPF);
    ddsMap.put(ObjectType.DSPF, CompCmd.CRTDSPF);
    ddsMap.put(ObjectType.LF, CompCmd.CRTLF);
    ddsMap.put(ObjectType.PRTF, CompCmd.CRTPRTF);
    typeToCmdMap.put(SourceType.DDS, ddsMap);

    /* Source type: CMD */
    Map<ObjectType, CompCmd> cmdMap = new EnumMap<>(ObjectType.class);
    cmdMap.put(ObjectType.CMD, CompCmd.CRTCMD);
    typeToCmdMap.put(SourceType.CMD, cmdMap);

    /* Source type: MNU */
    Map<ObjectType, CompCmd> mnuMap = new EnumMap<>(ObjectType.class);
    mnuMap.put(ObjectType.MNU, CompCmd.CRTMNU);
    typeToCmdMap.put(SourceType.MNU, mnuMap);

    /* Source type: QMQRY */
    Map<ObjectType, CompCmd> qmqryMap = new EnumMap<>(ObjectType.class);
    qmqryMap.put(ObjectType.QMQRY, CompCmd.CRTQMQRY);
    typeToCmdMap.put(SourceType.QMQRY, qmqryMap);

    /* Source type: BNDDIR */
    Map<ObjectType, CompCmd> bndDirMap = new EnumMap<>(ObjectType.class);
    bndDirMap.put(ObjectType.BNDDIR, CompCmd.CRTBNDDIR);
    typeToCmdMap.put(SourceType.BNDDIR, bndDirMap);

    /* Source type: DTAARA */
    Map<ObjectType, CompCmd> dtaaraMap = new EnumMap<>(ObjectType.class);
    dtaaraMap.put(ObjectType.DTAARA, CompCmd.CRTDTAARA);
    typeToCmdMap.put(SourceType.DTAARA, dtaaraMap);

    /* Source type: DTAQ */
    Map<ObjectType, CompCmd> dtaqMap = new EnumMap<>(ObjectType.class);
    dtaqMap.put(ObjectType.DTAQ, CompCmd.CRTDTAQ);
    typeToCmdMap.put(SourceType.DTAQ, dtaqMap);

    /* Source type: MSGF */
    Map<ObjectType, CompCmd> msgfMap = new EnumMap<>(ObjectType.class);
    msgfMap.put(ObjectType.MSGF, CompCmd.CRTMSGF);
    typeToCmdMap.put(SourceType.MSGF, msgfMap);

  }  

  /* 
   * System command patterns 
   */

  // CHGLIBL
  public static final List<ParamCmd> ChgLibLPattern = Arrays.asList(
    ParamCmd.LIBL,
    ParamCmd.CURLIB // Add USRLIBL if needed and added to ParamCmd
  );

  // CHGCURLIB
  public static final List<ParamCmd> ChgCurLibPattern = Arrays.asList(
    ParamCmd.CURLIB
  );

  // CRTBNDDIR
  public static final List<ParamCmd> BndDirPattern = Arrays.asList(
    ParamCmd.BNDDIR,
    ParamCmd.AUT,   
    ParamCmd.TEXT
  );

  // ADDBNDDIRE
  public static final List<ParamCmd> AddBndDirePattern = Arrays.asList(
    ParamCmd.BNDDIR,
    ParamCmd.OBJ,   
    ParamCmd.POSITION
  );

  // DLTOBJ
  public static final List<ParamCmd> DltObjPattern = Arrays.asList(
    ParamCmd.OBJ,   
    ParamCmd.OBJTYPE,
    ParamCmd.ASPDEV,
    ParamCmd.RMVMSG
  );

  // CRTDUPOBJ
  public static final List<ParamCmd> CrtDupObj_Pattern = Arrays.asList(
    ParamCmd.OBJ,   
    ParamCmd.FROMLIB,
    ParamCmd.OBJTYPE,
    ParamCmd.TOLIB,   
    ParamCmd.NEWOBJ,  
    ParamCmd.ASPDEV,  
    ParamCmd.TOASPDEV
  );

  // CHGCURDIR
  public static final List<ParamCmd> ChgCurDir_Pattern = Arrays.asList(
    ParamCmd.DIR
  );

  // RMVDIR
  public static final List<ParamCmd> RmvDir_Pattern = Arrays.asList(
    ParamCmd.DIR,
    ParamCmd.SUBTREE,
    ParamCmd.RMVLNK
  );

  // QSH
  public static final List<ParamCmd> Qsh_Pattern = Arrays.asList(
    ParamCmd.CMD
  );

  // CRTDTAARA
  public static final List<ParamCmd> CrtDtaAra_Pattern = Arrays.asList(
    ParamCmd.DTAARA,
    ParamCmd.TYPE,
    ParamCmd.LEN, 
    ParamCmd.VALUE,     
    ParamCmd.RMTDTAARA, 
    ParamCmd.RMTLOCNAME,
    ParamCmd.RDB,       
    ParamCmd.DEV,       
    ParamCmd.LCLLOCNAME,
    ParamCmd.MODE,      
    ParamCmd.RMTNETID,
    ParamCmd.TEXT,
    ParamCmd.AUT
  );

  // CRTDTAQ
  public static final List<ParamCmd> CrtDtaQ_Pattern = Arrays.asList(
    ParamCmd.DTAQ,
    ParamCmd.TYPE,  
    ParamCmd.MAXLEN,
    ParamCmd.FORCE, 
    ParamCmd.SEQ,
    ParamCmd.SENDERID,
    ParamCmd.SIZE,    
    ParamCmd.AUTORCL, 
    ParamCmd.TEXT,    
    ParamCmd.AUT
  );

  // CRTMSGF
  public static final List<ParamCmd> CrtMsgF_Pattern = Arrays.asList(
    ParamCmd.MSGF,
    ParamCmd.TEXT,
    ParamCmd.SIZE,
    ParamCmd.AUT,
    ParamCmd.CCSID
  );

  // ADDMSGD
  public static final List<ParamCmd> AddMsgD_Pattern = Arrays.asList(
    ParamCmd.MSGID,
    ParamCmd.MSGF,
    ParamCmd.MSG,
    ParamCmd.SECLVL,
    ParamCmd.SEV,
    ParamCmd.FMT,
    ParamCmd.TYPE,  
    ParamCmd.LEN,    
    ParamCmd.VALUES,
    ParamCmd.SPCVAL,
    ParamCmd.RANGE,
    ParamCmd.REL,
    ParamCmd.DFT,
    ParamCmd.DFTPGM,
    ParamCmd.DMPLST,
    ParamCmd.LVL,
    ParamCmd.ALROPT,
    ParamCmd.LOGPRB,
    ParamCmd.CCSID
  );

  // CALL
  public static final List<ParamCmd> Call_Pattern = Arrays.asList(
    ParamCmd.PGM,
    ParamCmd.PARM
  );

  // OVRDBF
  public static final List<ParamCmd> OvrDbfPattern = Arrays.asList(
    ParamCmd.FILE,
    ParamCmd.TOFILE,
    ParamCmd.MBR,
    ParamCmd.POSITION,
    ParamCmd.RCDFMTLCK,
    ParamCmd.FRCRATIO,
    ParamCmd.FMTSLR,
    ParamCmd.WAITFILE,
    ParamCmd.WAITRCD, 
    ParamCmd.REUSEDLT,
    ParamCmd.NBRRCDS, 
    ParamCmd.EOFDLY,  
    ParamCmd.LVLCHK,  
    ParamCmd.EXPCHK,  
    ParamCmd.INHWRT,  
    ParamCmd.SECURE,
    ParamCmd.OVRSCOPE,
    ParamCmd.SHARE,   
    ParamCmd.OPNSCOPE,
    ParamCmd.SEQONLY, 
    ParamCmd.DSTDTA
  );

  // OVRPRTF
  public static final List<ParamCmd> OvrPrtfPattern = Arrays.asList(
    ParamCmd.FILE,
    ParamCmd.TOFILE,
    ParamCmd.DEV,
    ParamCmd.DEVTYPE,
    ParamCmd.PAGESIZE,
    ParamCmd.LPI,
    ParamCmd.CPI,
    ParamCmd.FRONTMGN,
    ParamCmd.BACKMGN,
    ParamCmd.OVRFLW,
    ParamCmd.FOLD,
    ParamCmd.RPLUNPRT,
    ParamCmd.ALIGN,
    ParamCmd.DRAWER,
    ParamCmd.OUTBIN,
    ParamCmd.FONT,
    ParamCmd.FORMFEED,
    ParamCmd.PRTQLTY,
    ParamCmd.CTLCHAR,
    ParamCmd.CHLVAL, 
    ParamCmd.FIDELITY,
    ParamCmd.CHRID,   
    ParamCmd.DECFMT,
    ParamCmd.FNTCHRSET,
    ParamCmd.CDEFNT,
    ParamCmd.PAGDFN,
    ParamCmd.FORMDF,
    ParamCmd.AFPCHARS,
    ParamCmd.TBLREFCHR,
    ParamCmd.PAGRTT,   
    ParamCmd.MULTIUP,  
    ParamCmd.REDUCE,   
    ParamCmd.PRTTXT,   
    ParamCmd.JUSTIFY, 
    ParamCmd.DUPLEX,  
    ParamCmd.UOM,     
    ParamCmd.FRONTOVL,
    ParamCmd.BACKOVL,
    ParamCmd.CVTLINDTA, 
    ParamCmd.IPDSPASTHR,
    ParamCmd.USRRSCLIBL,
    ParamCmd.CORNERSTPL,
    ParamCmd.EDGESTITCH,
    ParamCmd.SADLSTITCH,
    ParamCmd.FNTRSL,
    ParamCmd.DFRWRT,
    ParamCmd.SPOOL, 
    ParamCmd.OUTQ,  
    ParamCmd.FORMTYPE, 
    ParamCmd.COPIES,   
    ParamCmd.PAGERANGE,
    ParamCmd.MAXRCDS,  
    ParamCmd.FILESEP,  
    ParamCmd.SCHEDULE, 
    ParamCmd.HOLD,     
    ParamCmd.SAVE,     
    ParamCmd.OUTPTY,   
    ParamCmd.USRDTA,   
    ParamCmd.SPLFOWN,  
    ParamCmd.USRDFNOPT,
    ParamCmd.USRDFNDTA,
    ParamCmd.USRDFNOBJ,
    ParamCmd.SPLFNAME, 
    ParamCmd.EXPDATE,  
    ParamCmd.DAYS,     
    ParamCmd.IGCDTA,   
    ParamCmd.IGCEXNCHR,
    ParamCmd.IGCCHRRTT,
    ParamCmd.IGCCPI,   
    ParamCmd.IGCSOSI,  
    ParamCmd.IGCCDEFNT,
    ParamCmd.TOSTMF,
    ParamCmd.WSCST,
    ParamCmd.WAITFILE,
    ParamCmd.LVLCHK,  
    ParamCmd.SECURE,  
    ParamCmd.OVRSCOPE,
    ParamCmd.SHARE,   
    ParamCmd.OPNSCOPE
  );

  // DLTOVR
  public static final List<ParamCmd> DltOvr_Pattern = Arrays.asList(
    ParamCmd.FILE,
    ParamCmd.LVL
  );

  // CRTSRCPF
  public static final List<ParamCmd> CrtSrcPfPattern = Arrays.asList(
    ParamCmd.FILE,
    ParamCmd.RCDLEN,
    ParamCmd.MBR,   
    ParamCmd.TEXT,
    ParamCmd.SYSTEM,   
    ParamCmd.EXPDATE,  
    ParamCmd.MAXMBRS,  
    ParamCmd.ACCPTHSIZ,
    ParamCmd.PAGESIZE, 
    ParamCmd.ACCPTH,   
    ParamCmd.MAINT,
    ParamCmd.RECOVER,  
    ParamCmd.FRCACCPTH,
    ParamCmd.SIZE,
    ParamCmd.ALLOCATE,
    ParamCmd.CONTIG,  
    ParamCmd.UNIT,    
    ParamCmd.FRCRATIO,
    ParamCmd.WAITFILE,
    ParamCmd.WAITRCD, 
    ParamCmd.SHARE,   
    ParamCmd.DLTPCT,  
    ParamCmd.CCSID,   
    ParamCmd.ALWUPD,
    ParamCmd.ALWDLT,
    ParamCmd.AUT
  );

  // ADDPFM
  public static final List<ParamCmd> AddPfmPattern = Arrays.asList(
    ParamCmd.FILE,
    ParamCmd.MBR, 
    ParamCmd.TEXT,
    ParamCmd.EXPDATE,
    ParamCmd.SHARE,  
    ParamCmd.SRCTYPE
  );

  // CPYFRMSTMF
  public static final List<ParamCmd> CpyFrmStmfPattern = Arrays.asList(
    ParamCmd.FROMSTMF,
    ParamCmd.TOMBR,
    ParamCmd.MBROPT,
    ParamCmd.CVTDTA,
    ParamCmd.STMFCCSID,
    ParamCmd.DBFCCSID,   
    ParamCmd.ENDLINFMT,  
    ParamCmd.TABEXPN,    
    ParamCmd.STMFCODPAG
  );

  // CPYTOSTMF
  public static final List<ParamCmd> CpyToStmfPattern = Arrays.asList(
    ParamCmd.FROMMBR,
    ParamCmd.TOSTMF,
    ParamCmd.STMFOPT,
    ParamCmd.CVTDTA,  
    ParamCmd.DBFCCSID,  
    ParamCmd.STMFCCSID,
    ParamCmd.ENDLINFMT,
    ParamCmd.AUT
  );    


  /* 
   * ILE Compilation Patterns 
   */

  // CRTSRVPGM
  public static final List<ParamCmd> Bnd_Srvpgm_Pattern = Arrays.asList(
    ParamCmd.SRVPGM,
    ParamCmd.MODULE,
    ParamCmd.EXPORT,  
    ParamCmd.SRCFILE, 
    ParamCmd.SRCMBR,  
    ParamCmd.SRCSTMF, 
    ParamCmd.TEXT,    
    ParamCmd.BNDSRVPGM,
    ParamCmd.BNDDIR,
    ParamCmd.ACTGRP,
    ParamCmd.OPTION,
    ParamCmd.DETAIL,
    ParamCmd.ALWUPD,    
    ParamCmd.ALWLIBUPD, 
    ParamCmd.USRPRF,    
    ParamCmd.REPLACE,   
    ParamCmd.AUT,       
    ParamCmd.TGTRLS,    
    ParamCmd.ALWRINZ,   
    ParamCmd.STGMDL,    
    ParamCmd.ARGOPT,    
    ParamCmd.IPA,       
    ParamCmd.IPACTLFILE

  );

  // CRTBNDRPG
  public static final List<ParamCmd> Rpgle_Pgm_Pattern = Arrays.asList(
    ParamCmd.PGM,       // Program
    ParamCmd.SRCFILE,   // Source file
    ParamCmd.SRCMBR,    // Source member
    ParamCmd.SRCSTMF,   // Source stream file

    ParamCmd.GENLVL,    // Generation severity level
    ParamCmd.TEXT,      // Text 'description'

    ParamCmd.DFTACTGRP,
    ParamCmd.ACTGRP,
    ParamCmd.STGMDL,

    ParamCmd.BNDDIR,    // Binding directory

    ParamCmd.OPTION,    // Compiler options

    ParamCmd.DBGVIEW,   // Debugging views
    ParamCmd.DBGENCKEY, // Debug encryption key
    ParamCmd.OUTPUT,    // Output
    ParamCmd.OPTIMIZE,  // Optimization level
    ParamCmd.INDENT,    // Source listing indentation
    ParamCmd.CVTOPT,    // Type conversion options

    ParamCmd.SRTSEQ,    // Sort sequence
    ParamCmd.LANGID,    // Sort sequence
    ParamCmd.REPLACE,
    ParamCmd.USRPRF,
    ParamCmd.AUT,
    ParamCmd.TRUNCNBR,
    ParamCmd.FIXNBR,
    ParamCmd.TGTRLS,
    ParamCmd.ALWNULL,
    ParamCmd.DEFINE,
    ParamCmd.ENBPFRCOL,

    ParamCmd.PRFDTA,
    ParamCmd.LICOPT,
    ParamCmd.INCDIR,
    ParamCmd.PGMINFO,

    ParamCmd.INFOSTMF,
    ParamCmd.PPGENOPT,
    ParamCmd.PPSRCFILE,
    ParamCmd.PPSRCMBR,
    ParamCmd.PPSRCSTMF,
    ParamCmd.TGTCCSID,
    ParamCmd.REQPREXP,
    ParamCmd.PPMINOUTLN

  );

  // CRTBNDCL
  public static final List<ParamCmd> Clle_Pgm_Pattern = Arrays.asList(
    ParamCmd.PGM,       // Program
    ParamCmd.SRCFILE,   // Source file
    ParamCmd.SRCMBR,    // Source member
    ParamCmd.SRCSTMF,   // Source stream file
    ParamCmd.TEXT,      // Text 'description'

    ParamCmd.DFTACTGRP,
    ParamCmd.ACTGRP,
    ParamCmd.STGMDL,
    ParamCmd.OUTPUT,

    ParamCmd.OPTION,
    ParamCmd.USRPRF,   
    ParamCmd.LOG,      
    ParamCmd.ALWRTVSRC,
    ParamCmd.REPLACE,  
    ParamCmd.TGTRLS,   
    ParamCmd.AUT,      
    ParamCmd.SRTSEQ,           
    ParamCmd.LANGID,   
    ParamCmd.OPTIMIZE, 
    ParamCmd.DBGVIEW,  
    ParamCmd.DBGENCKEY,
    ParamCmd.ENBPFRCOL,

    ParamCmd.INCFILE,
    ParamCmd.INCDIR,
    ParamCmd.TGTCCSID

  );

  /* Modules */

  // CRTRPGMOD
  public static final List<ParamCmd> Rpgle_Mod_Pattern = Arrays.asList(
    ParamCmd.MODULE, 
    ParamCmd.SRCFILE,
    ParamCmd.SRCMBR,    // Source member
    ParamCmd.SRCSTMF,   // Source stream file
    ParamCmd.GENLVL,    // Generation severity level
    ParamCmd.TEXT,      // Text 'description'
    ParamCmd.OPTION,    // Compiler options
    ParamCmd.DBGVIEW,   // Debugging views
    
    ParamCmd.DBGENCKEY, // Debug encryption key
    ParamCmd.OUTPUT,    // Output
    ParamCmd.OPTIMIZE,  // Optimization level 
    ParamCmd.INDENT,    // Source listing indentation
    ParamCmd.CVTOPT,    // Type conversion options
    ParamCmd.SRTSEQ,    // Sort sequence  
    ParamCmd.LANGID,    // Language identifier
    ParamCmd.REPLACE,   // Replace module
    ParamCmd.AUT,       // Authority
    ParamCmd.TRUNCNBR,  // Truncate numeric
    ParamCmd.FIXNBR,    // Fix numeric
    ParamCmd.TGTRLS,    // Target release 
    ParamCmd.ALWNULL,   // Allow null values  

    ParamCmd.DEFINE,    // Define condition names        
    ParamCmd.ENBPFRCOL, // Enable performance collection 
    ParamCmd.PRFDTA,    // Profiling data
    ParamCmd.STGMDL,    // Storage model
    ParamCmd.BNDDIR,    // Binding directory

    ParamCmd.LICOPT,    // Licensed Internal Code options
    ParamCmd.INCDIR,    // Include directory
    ParamCmd.PGMINFO,   // Program interface information 
    
    ParamCmd.INFOSTMF,  // Program interface stream file
    ParamCmd.PPGENOPT,  // Preprocessor options 
    ParamCmd.PPSRCFILE, // Output source file
    ParamCmd.PPSRCMBR,  // Output source member
    ParamCmd.PPSRCSTMF, // Output stream file
    ParamCmd.TGTCCSID,  // Target CCSID
    ParamCmd.REQPREXP,  // Require prototype for export 
    ParamCmd.PPMINOUTLN // MINIMUM OUTPUT LINE LENGTH
  );

  // CRTCLMOD
  public static final List<ParamCmd> Clle_Mod_Pattern = Arrays.asList(
    ParamCmd.MODULE, 
    ParamCmd.SRCFILE,
    ParamCmd.SRCMBR, 
    ParamCmd.SRCSTMF,
    ParamCmd.TEXT,   
    ParamCmd.OUTPUT, 
    ParamCmd.OPTION,   

    ParamCmd.LOG,
    ParamCmd.ALWRTVSRC, 
    ParamCmd.REPLACE,   
    ParamCmd.TGTRLS,    
    ParamCmd.AUT,       
    ParamCmd.SRTSEQ,    
    ParamCmd.LANGID,    
    ParamCmd.OPTIMIZE,  
    ParamCmd.DBGVIEW,   
    ParamCmd.DBGENCKEY, 
    ParamCmd.ENBPFRCOL, 
    ParamCmd.INCFILE,  
    ParamCmd.INCDIR,
    ParamCmd.TGTCCSID

  );

  /* Sql and RPG */

  // CRTSQLRPGI
  public static final List<ParamCmd> SqlRpgle_Pgm_Mod_Pattern = Arrays.asList(
    ParamCmd.OBJ, 
    ParamCmd.SRCFILE,
    ParamCmd.SRCMBR, 
    ParamCmd.SRCSTMF,
    ParamCmd.COMMIT,  
    ParamCmd.RDB,     
    ParamCmd.OBJTYPE, 
    ParamCmd.OUTPUT,  
    ParamCmd.TEXT,   
    ParamCmd.OPTION,    
    ParamCmd.RPGPPOPT,  
    ParamCmd.TGTRLS,    
    ParamCmd.INCFILE,   
    ParamCmd.INCDIR,    
    ParamCmd.ALWCPYDTA, 
    ParamCmd.CLOSQLCSR, 
    ParamCmd.ALWBLK,    
    ParamCmd.DLYPRP,    
    ParamCmd.CONACC,       
    ParamCmd.GENLVL,    
    ParamCmd.DATFMT,    
    ParamCmd.DATSEP,    
    ParamCmd.TIMFMT,    
    ParamCmd.TIMSEP,    
    ParamCmd.REPLACE,   
    ParamCmd.RDBCNNMTH, 
    ParamCmd.DFTRDBCOL, 
    ParamCmd.DYNDFTCOL, 
    ParamCmd.SQLPKG, 
    ParamCmd.SQLPATH,
    ParamCmd.SQLCURR,
    ParamCmd.SAAFLAG,
    ParamCmd.FLAGSTD,
    ParamCmd.PRTFILE,  
    ParamCmd.DBGVIEW,  
    ParamCmd.DBGENCKEY,
    ParamCmd.USRPRF,   
    ParamCmd.DYNUSRPRF,
    ParamCmd.SRTSEQ,   
    ParamCmd.LANGID,   
    ParamCmd.CVTCCSID, 
    ParamCmd.TOSRCFILE,
    ParamCmd.DECRESULT,
    ParamCmd.DECFLTRND, 
    ParamCmd.COMPILEOPT

  );

  /* OPM */

  // CRTRPGPGM
  public static final List<ParamCmd> Rpg_Pgm_Pattern = Arrays.asList(
    ParamCmd.PGM,       // Program
    ParamCmd.SRCFILE,   // Source file
    ParamCmd.SRCMBR,    // Source member
    ParamCmd.GENLVL,   
    ParamCmd.TEXT,   

    ParamCmd.OPTION,   
    ParamCmd.GENOPT,   
    ParamCmd.INDENT,   

    ParamCmd.CVTOPT,  
    ParamCmd.SRTSEQ,  
    ParamCmd.LANGID,  
    ParamCmd.SAAFLAG, 
    ParamCmd.PRTFILE, 
    ParamCmd.REPLACE, 
    ParamCmd.TGTRLS,  
    ParamCmd.USRPRF,  
    ParamCmd.AUT,     
    ParamCmd.PHSTRC,  
    ParamCmd.ITDUMP,  

    ParamCmd.SNPDUMP,   
    ParamCmd.CODELIST,  
    ParamCmd.IGNDECERR, 
    ParamCmd.ALWNULL  

  );

  // CRTCLPGM
  public static final List<ParamCmd> Clp_Pgm_Pattern = Arrays.asList(
    ParamCmd.PGM,       // Program
    ParamCmd.SRCFILE,   // Source file
    ParamCmd.SRCMBR,    // Source member
    ParamCmd.TEXT,   

    ParamCmd.OUTPUT,
    ParamCmd.OPTION,
    ParamCmd.GENOPT,
    ParamCmd.USRPRF,

    ParamCmd.LOG,      
    ParamCmd.ALWRTVSRC,
    ParamCmd.REPLACE,  
    ParamCmd.TGTRLS,   
    ParamCmd.AUT,      
    ParamCmd.SRTSEQ,   
    ParamCmd.LANGID,   
    ParamCmd.INCFILE  

  );

  /* Sql */

  // RUNSQLSTM
  public static final List<ParamCmd> Sql_Pattern = Arrays.asList(
    ParamCmd.SRCFILE,   // Source file
    ParamCmd.SRCMBR,    // Source member
    ParamCmd.SRCSTMF,   // Source stream file
    ParamCmd.COMMIT,    // Commitment control
    ParamCmd.NAMING,    // Naming
    ParamCmd.ERRLVL,    // Severity level
    ParamCmd.DATFMT,    // Date format
    ParamCmd.DATSEP,    // Date separator character
    ParamCmd.TIMFMT,    // Time format
    ParamCmd.TIMSEP,    // Time separator character
    ParamCmd.MARGINS,   // Source margins
    ParamCmd.DFTRDBCOL, // Default collection
    ParamCmd.SAAFLAG,   // IBM SQL flagging
    ParamCmd.FLAGSTD,   // ANS flagging
    ParamCmd.DECMPT,    // Decimal point
    ParamCmd.SRTSEQ,    // Sort sequence
    ParamCmd.LANGID,    // Language id
    ParamCmd.OPTION,    // Source listing options
    ParamCmd.PRTFILE,   // Print file
    ParamCmd.PROCESS,   // Statement processing
    ParamCmd.SECLVLTXT, // Second level text 
    ParamCmd.ALWCPYDTA, // Allow copy of data
    ParamCmd.ALWBLK,    // Allow blocking 
    ParamCmd.SQLCURRULE,// SQL rules
    ParamCmd.DECRESULT, // Decimal result options
    ParamCmd.CONACC,    // Concurrent access resolution
    ParamCmd.SYSTIME,   // System time sensitive 
    ParamCmd.OUTPUT,    // Listing output
    ParamCmd.TGTRLS,    // Target release
    ParamCmd.DBGVIEW,   // Debugging view
    ParamCmd.CLOSQLCSR, // Close SQL cursor 
    ParamCmd.DLYPRP,    // Delay PREPARE 
    ParamCmd.USRPRF,    // User profile
    ParamCmd.DYNUSRPRF  // Dynamic user profile

  );

  /* DDS Files */

  // CRTDSPF
  public static final List<ParamCmd> Dds_Dspf_Pattern = Arrays.asList(
    ParamCmd.FILE,    
    ParamCmd.SRCFILE, 
    ParamCmd.SRCMBR,  
    ParamCmd.GENLVL,  
    ParamCmd.FLAG,    
    ParamCmd.DEV,     
    ParamCmd.TEXT,
    ParamCmd.OPTION,
    ParamCmd.MAXDEV, 
    ParamCmd.ENHDSP, 
    ParamCmd.RSTDSP, 
    ParamCmd.DFRWRT, 
    ParamCmd.CHRID,  
    ParamCmd.DECFMT,    
    ParamCmd.SFLENDTXT, 
    ParamCmd.WAITFILE,  
    ParamCmd.WAITRCD,   
    ParamCmd.DTAQ,      
    ParamCmd.SHARE, 
    ParamCmd.SRTSEQ,
    ParamCmd.LANGID,  
    ParamCmd.LVLCHK,  
    ParamCmd.AUT,     
    ParamCmd.REPLACE
  );

  // CRTPF
  public static final List<ParamCmd> Dds_Pf_Pattern = Arrays.asList(
    ParamCmd.FILE,
    ParamCmd.SRCFILE,
    ParamCmd.SRCMBR,  
    ParamCmd.RCDLEN,  
    ParamCmd.GENLVL,  
    ParamCmd.FLAG,    
    ParamCmd.FILETYPE,
    ParamCmd.MBR,     
    ParamCmd.TEXT,    
    ParamCmd.OPTION,
    ParamCmd.SYSTEM,   
    ParamCmd.EXPDATE,  
    ParamCmd.MAXMBRS,  
    ParamCmd.ACCPTHSIZ,
    ParamCmd.PAGESIZE, 
    ParamCmd.MAINT,    
    ParamCmd.RECOVER,  
    ParamCmd.FRCACCPTH,
    ParamCmd.SIZE,
    ParamCmd.ALLOCATE,
    ParamCmd.CONTIG,  
    ParamCmd.UNIT,    
    ParamCmd.FRCRATIO,
    ParamCmd.WAITFILE,
    ParamCmd.WAITRCD, 
    ParamCmd.SHARE,   
    ParamCmd.DLTPCT,  
    ParamCmd.REUSEDLT,
    ParamCmd.SRTSEQ,       
    ParamCmd.LANGID,  
    ParamCmd.CCSID, 
    ParamCmd.ALWUPD,
    ParamCmd.ALWDLT,
    ParamCmd.LVLCHK,
    ParamCmd.NODGRP,
    ParamCmd.PTNKEY,
    ParamCmd.AUT
  );

  // CRTLF
  public static final List<ParamCmd> Dds_Lf_Pattern = Arrays.asList(
    ParamCmd.FILE,
    ParamCmd.SRCFILE,
    ParamCmd.SRCMBR,  
    ParamCmd.GENLVL,  
    ParamCmd.FLAG,    
    ParamCmd.FILETYPE,
    ParamCmd.MBR,     
    ParamCmd.DTAMBRS, 
    ParamCmd.TEXT,
    ParamCmd.OPTION,
    ParamCmd.SYSTEM,    
    ParamCmd.MAXMBRS,   
    ParamCmd.ACCPTHSIZ, 
    ParamCmd.PAGESIZE,  
    ParamCmd.MAINT,     
    ParamCmd.RECOVER,   
    ParamCmd.FRCACCPTH, 
    ParamCmd.UNIT,      
    ParamCmd.FMTSLR,
    ParamCmd.FRCRATIO, 
    ParamCmd.WAITFILE, 
    ParamCmd.WAITRCD,  
    ParamCmd.SHARE,    
    ParamCmd.SRTSEQ,   
    ParamCmd.LANGID,   
    ParamCmd.LVLCHK,   
    ParamCmd.AUT 
  );

  // CRTPRTF
  public static final List<ParamCmd> Dds_Prtf_Pattern = Arrays.asList(
    ParamCmd.FILE,   
    ParamCmd.SRCFILE,
    ParamCmd.SRCMBR, 
    ParamCmd.GENLVL, 
    ParamCmd.FLAG,   
    ParamCmd.DEV,    
    ParamCmd.DEVTYPE,
    ParamCmd.TEXT,   
    ParamCmd.OPTION,
    ParamCmd.PAGESIZE,
    ParamCmd.LPI,     
    ParamCmd.CPI,     
    ParamCmd.FRONTMGN,
    ParamCmd.BACKMGN,
    ParamCmd.OVRFLW,  
    ParamCmd.FOLD,    
    ParamCmd.RPLUNPRT,
    ParamCmd.ALIGN,   
    ParamCmd.CTLCHAR, 
    ParamCmd.CHLVAL,  
    ParamCmd.FIDELITY,
    ParamCmd.PRTQLTY,  
    ParamCmd.FORMFEED, 
    ParamCmd.DRAWER,   
    ParamCmd.OUTBIN,   
    ParamCmd.FONT,     
    ParamCmd.CHRID,    
    ParamCmd.DECFMT,   
    ParamCmd.FNTCHRSET,
    ParamCmd.CDEFNT,
    ParamCmd.TBLREFCHR,
    ParamCmd.PAGDFN,   
    ParamCmd.FORMDF,   
    ParamCmd.AFPCHARS,
    ParamCmd.PAGRTT,   
    ParamCmd.MULTIUP,  
    ParamCmd.REDUCE,   
    ParamCmd.PRTTXT,   
    ParamCmd.JUSTIFY,  
    ParamCmd.DUPLEX,   
    ParamCmd.UOM,      
    ParamCmd.FRONTOVL, 
    ParamCmd.BACKOVL,
    ParamCmd.CVTLINDTA, 
    ParamCmd.IPDSPASTHR,
    ParamCmd.USRRSCLIBL,
    ParamCmd.CORNERSTPL,
    ParamCmd.EDGESTITCH,
    ParamCmd.SADLSTITCH,
    ParamCmd.FNTRSL,
    ParamCmd.DFRWRT,
    ParamCmd.SPOOL, 
    ParamCmd.OUTQ,  
    ParamCmd.FORMTYPE, 
    ParamCmd.COPIES,   
    ParamCmd.EXPDATE,  
    ParamCmd.DAYS,     
    ParamCmd.PAGERANGE,
    ParamCmd.MAXRCDS, 
    ParamCmd.FILESEP, 
    ParamCmd.SCHEDULE,
    ParamCmd.HOLD,    
    ParamCmd.SAVE,    
    ParamCmd.OUTPTY,  
    ParamCmd.USRDTA,  
    ParamCmd.SPLFOWN, 
    ParamCmd.USRDFNOPT,
    ParamCmd.USRDFNDTA,
    ParamCmd.USRDFNOBJ,
    ParamCmd.TOSTMF,   
    ParamCmd.WSCST,    
    ParamCmd.WAITFILE, 
    ParamCmd.SHARE,   
    ParamCmd.LVLCHK,   
    ParamCmd.AUT,      
    ParamCmd.REPLACE
  );

  // CRTCMD
  public static final List<ParamCmd> Cmd_Pattern = Arrays.asList(
    ParamCmd.CMD,
    ParamCmd.PGM,        
    ParamCmd.SRCFILE,    
    ParamCmd.SRCMBR,     
    ParamCmd.SRCSTMF,    
    ParamCmd.REXSRCFILE, 
    ParamCmd.REXSRCMBR,  
    ParamCmd.REXCMDENV,  
    ParamCmd.REXEXITPGM,
    ParamCmd.THDSAFE,
    ParamCmd.MLTTHDACN,
    ParamCmd.TEXT,
    ParamCmd.OPTION,
    ParamCmd.VLDCKR,
    ParamCmd.MODE,
    ParamCmd.ALLOW,
    ParamCmd.ALWLMTUSR,
    ParamCmd.MAXPOS,   
    ParamCmd.PMTFILE,  
    ParamCmd.MSGF,
    ParamCmd.HLPSHELF, 
    ParamCmd.HLPPNLGRP,
    ParamCmd.HLPID,
    ParamCmd.HLPSCHIDX,
    ParamCmd.CURLIB,   
    ParamCmd.PRDLIB,   
    ParamCmd.PMTOVRPGM,
    ParamCmd.AUT,      
    ParamCmd.REPLACE,  
    ParamCmd.ENBGUI,   
    ParamCmd.TGTCCSID
  );


  // CRTMNU
  public static final List<ParamCmd> Mnu_Pattern = Arrays.asList(
    ParamCmd.MENU,   
    ParamCmd.TYPE,   
    ParamCmd.DSPF,   
    ParamCmd.MSGF,   
    ParamCmd.CMDLIN, 
    ParamCmd.DSPKEY, 
    ParamCmd.PGM,    
    ParamCmd.SRCFILE,
    ParamCmd.SRCMBR, 
    ParamCmd.OPTION, 
    ParamCmd.INCFILE,
    ParamCmd.CURLIB, 
    ParamCmd.PRDLIB, 
    ParamCmd.CHRID,  
    ParamCmd.REPLACE,
    ParamCmd.TEXT,   
    ParamCmd.AUT
  );

  // CRTQMQRY
  public static final List<ParamCmd> Qmqry_Pattern = Arrays.asList(
    ParamCmd.QMQRY,
    ParamCmd.SRCFILE,
    ParamCmd.SRCMBR,
    ParamCmd.TEXT,
    ParamCmd.SRTSEQ,
    ParamCmd.LANGID,
    ParamCmd.AUT,
    ParamCmd.REPLACE
  );

  public static final Map<Command, List<ParamCmd>> commandToPatternMap = new HashMap<>();

  static {
    /* Libraries */
    commandToPatternMap.put(SysCmd.CHGLIBL, ChgLibLPattern);
    commandToPatternMap.put(SysCmd.CHGCURLIB, ChgCurLibPattern);
    /* Bind dir */
    commandToPatternMap.put(SysCmd.ADDBNDDIRE, AddBndDirePattern);
    /* Objects */
    commandToPatternMap.put(SysCmd.DLTOBJ, DltObjPattern);
    commandToPatternMap.put(SysCmd.CRTDUPOBJ, CrtDupObj_Pattern);
    /* stream files */
    commandToPatternMap.put(SysCmd.CHGCURDIR, ChgCurDir_Pattern);
    commandToPatternMap.put(SysCmd.RMVDIR, RmvDir_Pattern);
    /* Pase */
    commandToPatternMap.put(SysCmd.QSH, Qsh_Pattern);
    /* Messages */
    commandToPatternMap.put(SysCmd.ADDMSGD, AddMsgD_Pattern);
    /* Pgms */
    commandToPatternMap.put(SysCmd.CALL, Call_Pattern);
    /* Ovr */
    commandToPatternMap.put(SysCmd.OVRDBF, OvrDbfPattern);
    commandToPatternMap.put(SysCmd.OVRPRTF, OvrPrtfPattern);
    commandToPatternMap.put(SysCmd.DLTOVR, DltOvr_Pattern);
    /* Source pf */
    commandToPatternMap.put(SysCmd.CRTSRCPF, CrtSrcPfPattern);
    commandToPatternMap.put(SysCmd.ADDPFM, AddPfmPattern);
    /* Migration */
    commandToPatternMap.put(SysCmd.CPYFRMSTMF, CpyFrmStmfPattern);
    commandToPatternMap.put(SysCmd.CPYTOSTMF, CpyToStmfPattern);

    /* 
     * Maps compilation command to its pattern 
     */ 

    /* ILE */
    commandToPatternMap.put(CompCmd.CRTSRVPGM, Bnd_Srvpgm_Pattern);
    commandToPatternMap.put(CompCmd.CRTBNDRPG, Rpgle_Pgm_Pattern);
    commandToPatternMap.put(CompCmd.CRTBNDCL, Clle_Pgm_Pattern);
    commandToPatternMap.put(CompCmd.CRTRPGMOD, Rpgle_Mod_Pattern);
    commandToPatternMap.put(CompCmd.CRTCLMOD, Clle_Mod_Pattern);
    commandToPatternMap.put(CompCmd.CRTSQLRPGI, SqlRpgle_Pgm_Mod_Pattern);
    /* OPM */
    commandToPatternMap.put(CompCmd.CRTRPGPGM, Rpg_Pgm_Pattern);
    commandToPatternMap.put(CompCmd.CRTCLPGM, Clp_Pgm_Pattern);
    /* SQL */
    commandToPatternMap.put(CompCmd.RUNSQLSTM, Sql_Pattern);
    /* DDS */
    commandToPatternMap.put(CompCmd.CRTDSPF, Dds_Dspf_Pattern);
    commandToPatternMap.put(CompCmd.CRTPF, Dds_Pf_Pattern);
    commandToPatternMap.put(CompCmd.CRTLF, Dds_Lf_Pattern);
    commandToPatternMap.put(CompCmd.CRTPRTF, Dds_Prtf_Pattern);
    /* CMD */
    commandToPatternMap.put(CompCmd.CRTCMD, Cmd_Pattern);
    /* MNU */
    commandToPatternMap.put(CompCmd.CRTMNU, Mnu_Pattern);
    /* QMQRY */
    commandToPatternMap.put(CompCmd.CRTQMQRY, Qmqry_Pattern);
    /* BNDDIR */
    commandToPatternMap.put(CompCmd.CRTBNDDIR, BndDirPattern);
    /* DTAARA */
    commandToPatternMap.put(CompCmd.CRTDTAARA, CrtDtaAra_Pattern);
    /* DTAQ */
    commandToPatternMap.put(CompCmd.CRTDTAQ, CrtDtaQ_Pattern);
    /* MSGF */
    commandToPatternMap.put(CompCmd.CRTMSGF, CrtMsgF_Pattern);
  }

  /* Return compilation command */
  public static CompCmd getCompilationCommand(SourceType sourceType, ObjectType objectType){
    return typeToCmdMap.get(sourceType).get(objectType);
  }

  /* Return command pattern */
  public static List<ParamCmd> getCommandPattern(Command cmd){
    return commandToPatternMap.getOrDefault(cmd, Collections.emptyList());
  }

  /* Return if source is opm */
  public static boolean isOpm(SourceType sourceType){
    return IleSources.contains(sourceType);
  }

}
