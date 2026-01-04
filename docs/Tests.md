# Tests

Something can be good and bad at different points in life, so don't trust Greek philosophers too much. 

The same applies for tests. They allow you to have a convenient verification method, but they also can tie you up to the current architecture. This may be what you want or not. So you need to take that into account.

On new projects, don't write a test till you have a good enough solid base; otherwise, you will be verifying the wrong thing, which leads to nothing.

## Unit tests

Since **MC** is built modularly, this allows us to test pure Java classes individually. These classes are easier to test because they don't need to mock anything or connect to any remote server or any other fancy stuff. Just instantiate the object, perform some operation, and validate the result. 

These classes are very important because they contain core logic functionality.

* [ArgParser Tests](../src/test/java/com/github/kraudy/compiler/ArgParserTest.java)
* [ParamMap Test](../src/test/java/com/github/kraudy/compiler/ParamMapTest.java)
* [ParamValue Test](../src/test/java/com/github/kraudy/compiler/ParamValueTest.java)
* [TargetKey Test](../src/test/java/com/github/kraudy/compiler/TargetKeyTest.java)
* [Utilities Test](../src/test/java/com/github/kraudy/compiler/UtilitiesTest.java)

These test are executed automatically with every `mvn clean package`. 

## Integration tests

Integration tests are a different beast, especially on IBM i-related projects. 

Here is the problem: **You want to test the full application stack; it needs to be reproducible anywhere and by anyone. Run on any remote IBM i server and be executed automatically from your laptop**.

Quite a challenge, right? **MC** just solved this problem and shows a real test: The official [TOBi recursive](https://github.com/IBM/tobi-example) example is build on a remote real hardware.

Create a `.env` file like this at project root

```bash
IBMI_HOSTNAME=BIGIRON.COM
IBMI_USERNAME=BIGBOY
IBMI_PASSWORD=BIGMONEY
```

Then execute `mvn clean verify -Pintegration`

That's it.

[StreamCompilation Integration Test](../src/test/java/com/github/kraudy/compiler/StreamCompilationIT.java)

* Connects to a real IBM I
* Upload source files from  [../src/test/resources/tobiRecursive](../src/test/resources/tobiRecursive)
* Execute compilation flow remotely
* Generate log
* Remove created objects

Here is the [tobi-example yaml spec](../src/test/resources/tobiRecursive/tobi.yaml)

```yaml
# Global defaults
defaults:
  TGTRLS: CURRENT
  DBGVIEW: ALL
  OPTION: EVENTF
  REPLACE: YES
  TGTCCSID: JOB

before:
  CrtBndDir:
    BNDDIR: SAMPLE

after: 
  DltObj: 
    OBJ: SAMPLE
    OBJTYPE: BndDir

failure: 
  DltObj: 
    OBJ: SAMPLE
    OBJTYPE: BndDir

targets:
  # Simple rpgle compilation
  curlib.ADDnum.pgm.rpgle:
    params:
      SRCSTMF: tobiRecursive/QRPGLESRC/ADDNUM.RPGLE
  
  # Files used by reference
  curlib.SAMREF.PF.dds:
    params:
      SRCSTMF: tobiRecursive/common/SAMREF.PF.dds

  curlib.vatdef.pf.dds:
    params:
      SRCSTMF: tobiRecursive/functionsVAT/vatdef.pf.dds

  # ART200 compilation context
  curlib.ARTICLE.pf.dds: # PF of ART200
    params:
      SRCSTMF: tobiRecursive/QDDSSRC/ARTICLE.pf.dds

  curlib.ARTICLE1.lf.dds: # LF of ARTICLE
    params:
      SRCSTMF: tobiRecursive/QDDSSRC/ARTICLE1.lf.dds

  curlib.ARTICLE2.lf.dds: # LF of ARTICLE
    params:
      SRCSTMF: tobiRecursive/QDDSSRC/ARTICLE2.lf.dds

  curlib.ART200D.dspf.dds: # DSPF of ART200
    params:
      SRCSTMF: tobiRecursive/QDDSSRC/ART200D.dspf.dds

  curlib.FAMILLY.PF.dds: # PF of module FAM300
    params:
      SRCSTMF: tobiRecursive/QDDSSRC/FAMILLY.PF.dds
  
  curlib.FAMILL1.LF.dds: # LF of PF FAMILLY
    params:
      SRCSTMF: tobiRecursive/QDDSSRC/FAMILL1.LF.dds

  curlib.FAM300.module.RPGLE: # Module of FFAMILLY SrvPgm
    params:
      SRCSTMF: tobiRecursive/QRPGLESRC/FAM300.module.RPGLE

  curlib.FAM301D.DSPF.dds: # DSPF of module FAM301
    params:
      SRCSTMF: tobiRecursive/QDDSSRC/FAM301D.DSPF.dds

  curlib.FAM301.module.RPGLE: # Module of FFAMILLY SrvPgm
    params:
      SRCSTMF: tobiRecursive/QRPGLESRC/FAM301.module.RPGLE

  curlib.FFAMILLY.srvpgm.BND: # SrvPgm of ART200
    params:
      SRCSTMF: tobiRecursive/QSRVSRC/FFAMILLY.srvpgm.BND
      MODULE:
        - FAM300
        - FAM301

  curlib.ART200.pgm.sqlrpgle: # Pgm
    before:
      AddBndDirE:   # Add srvpgm required by ART200
        BndDir: SAMPLE
        Obj: FFAMILLY

    params:
      SRCSTMF: tobiRecursive/QRPGLESRC/ART200.pgm.sqlrpgle

  # Context of ART201
  curlib.PROVIDER.PF.dds:
    params:
      SRCSTMF: tobiRecursive/QDDSSRC/PROVIDER.PF.dds

  curlib.PROVIDE1.LF.dds:
    params:
      SRCSTMF: tobiRecursive/QDDSSRC/PROVIDE1.LF.dds

  curlib.ARTIPROV.PF.dds: # PF of ART201
    params:
      SRCSTMF: tobiRecursive/QDDSSRC/ARTIPROV.PF.dds

  curlib.ARTIPRO1.LF.dds: # LF of ARTIPROV
    params:
      SRCSTMF: tobiRecursive/QDDSSRC/ARTIPRO1.LF.dds

  curlib.ART201D.dspf.dds: # DSPF of PGM ART201
    params:
      SRCSTMF: tobiRecursive/QDDSSRC/ART201D.dspf.dds

  curlib.ART300.module.rpgle: # Module of FARTICLE SrvPgm
    params:
      SRCSTMF: tobiRecursive/QRPGLESRC/ART300.module.rpgle

  curlib.ART301D.dspf.dds: # DSPF of ART301
    params:
      SRCSTMF: tobiRecursive/QDDSSRC/ART301D.dspf.dds

  curlib.ART301.module.SQLRPGLE: # Module of FARTICLE SrvPgm
    params:
      SRCSTMF: tobiRecursive/QRPGLESRC/ART301.module.SQLRPGLE

  curlib.FARTICLE.srvpgm.BND: # SrvPgm of ART201
    params:
      SRCSTMF: tobiRecursive/QSRVSRC/FARTICLE.srvpgm.BND
      MODULE:
        - ART300
        - ART301
      BNDSRVPGM: FFAMILLY
      TEXT: Function Article

  curlib.PRO300.module.RPGLE:
    params:
      SRCSTMF: tobiRecursive/QRPGLESRC/PRO300.module.RPGLE

  curlib.FPROVIDER.srvpgm.BND:
    params:
      SRCSTMF: tobiRecursive/QSRVSRC/FPROVIDER.srvpgm.BND
      MODULE: PRO300
      TEXT: Functions Provider

  curlib.ART201.pgm.rpgle: 
    before:
      AddBndDirE:   # Add srvpgm required by ART201
        BndDir: SAMPLE
        Obj: 
          - FARTICLE
          - FPROVIDER

    params:
      SRCSTMF: tobiRecursive/QRPGLESRC/ART201.pgm.rpgle

  # Context of ART202
  curlib.ART202D.dspf.dds: # DSPF of ART202
    params:
      SRCSTMF: tobiRecursive/QDDSSRC/ART202D.dspf.dds

  curlib.ART202.PGM.RPGLE: 
    params:
      SRCSTMF: tobiRecursive/QRPGLESRC/ART202.PGM.RPGLE

  # Context of LOG100

  curlib.PARAMETER.PF.dds: # PF of LOG100
    params:
      SRCSTMF: tobiRecursive/QDDSSRC/PARAMETER.PF.dds

  curlib.LOG100.PGM.RPGLE: 
    params:
      SRCSTMF: tobiRecursive/QRPGLESRC/LOG100.PGM.RPGLE

  # Context of ORD100

  curlib.DETORD.PF.dds: # PF of ORD100
    params:
      SRCSTMF: tobiRecursive/QDDSSRC/DETORD.PF.dds

  curlib.DETORD1.LF.dds: # LF of DETORD
    params:
      SRCSTMF: tobiRecursive/QDDSSRC/DETORD1.LF.dds

  curlib.ORDER.PF.dds: # PF of ORD100
    params:
      SRCSTMF: tobiRecursive/QDDSSRC/ORDER.PF.dds

  curlib.ORDER3.LF.dds: # LF of ORDER
    params:
      SRCSTMF: tobiRecursive/QDDSSRC/ORDER3.LF.dds

  curlib.ORD100D.DSPF.dds: # DSPF of ORD100
    params:
      SRCSTMF: tobiRecursive/QDDSSRC/ORD100D.DSPF.dds

  curlib.vat300.module.rpgle: # Module of FVAT SrvPgm
    params:
      SRCSTMF: tobiRecursive/functionsVAT/vat300.module.rpgle

  curlib.fvat.srvpgm.bnd: # SrvPgm of ORD100
    params:
      SRCSTMF: tobiRecursive/functionsVAT/fvat.srvpgm.bnd
      MODULE: VAT300
      TEXT: Functions VAT

  curlib.CUSTOMER.PF.dds: # PF of CUS300
    params:
      SRCSTMF: tobiRecursive/QDDSSRC/CUSTOMER.PF.dds

  curlib.CUSTOME1.LF.dds: # LF of CUSTOMER PF
    params:
      SRCSTMF: tobiRecursive/QDDSSRC/CUSTOME1.LF.dds

  curlib.CUS300.module.RPGLE: # Module of FCUSTOMER SrvPgm
    params:
      SRCSTMF: tobiRecursive/QRPGLESRC/CUS300.module.RPGLE

  curlib.CUS301D.dspf.dds: # DSPF of CUS301
    params:
      SRCSTMF: tobiRecursive/QDDSSRC/CUS301D.dspf.dds

  curlib.CUS301.module.SQLRPGLE: # Module of FCUSTOMER SrvPgm
    params:
      SRCSTMF: tobiRecursive/QRPGLESRC/CUS301.module.SQLRPGLE

  curlib.FCUSTOMER.srvpgm.BND: # SrvPgm of ORD100
    params:
      SRCSTMF: tobiRecursive/QSRVSRC/FCUSTOMER.srvpgm.BND
      MODULE: 
        - CUS300
        - CUS301
      TEXT: Functions Customer

  curlib.ORD100.PGM.RPGLE: 
    before:
      OvrDbf: 
        File: tmpdetord
        ToFile: detord
        OvrScope: Job
      
      AddBndDirE:   # Add srvpgm required by ORD100
        BndDir: SAMPLE
        Obj: 
          - fvat
          - FCUSTOMER

    params:
      SRCSTMF: tobiRecursive/QRPGLESRC/ORD100.PGM.RPGLE

    after:
      DltOvr: 
        File: tmpdetord
        LVL: Job

  # Context of ORD101
  
  curlib.ORDER1.LF.dds: # LF of ORDER
    params:
      SRCSTMF: tobiRecursive/QDDSSRC/ORDER1.LF.dds

  curlib.ORD101D.DSPF.dds: # DSPF of ORD101
    params:
      SRCSTMF: tobiRecursive/QDDSSRC/ORD101D.DSPF.dds

  curlib.ORD101.PGM.RPGLE: 
    params:
      SRCSTMF: tobiRecursive/QRPGLESRC/ORD101.PGM.RPGLE
```

Here is a resume integration test log

```diff
+14:30:13.658 [main] INFO  c.g.kraudy.compiler.MasterCompiler - 
Chain of commands: CRTBNDDIR BNDDIR(*CURLIB/SAMPLE) => CRTBNDRPG PGM(*CURLIB/ADDNUM) SRCSTMF(''/home/ROBKRAUDY/test_1767558132566/tobiRecursive/QRPGLESRC/ADDNUM.RPGLE'') OPTION(*EVENTF) DBGVIEW(*ALL) REPLACE(*YES) TGTRLS(*CURRENT) TGTCCSID(*JOB) => CPYFRMSTMF FROMSTMF(''/home/ROBKRAUDY/test_1767558132566/tobiRecursive/common/SAMREF.PF.dds'') TOMBR(''/QSYS.lib/ROBKRAUDY1.lib/QPFSRC.file/SAMREF.mbr'') MBROPT(*REPLACE) CVTDTA(*AUTO) STMFCODPAG(1208) => CRTPF FILE(*CURLIB/SAMREF) SRCFILE(ROBKRAUDY1/QPFSRC) SRCMBR(SAMREF) OPTION(*EVENTF) => CPYFRMSTMF FROMSTMF(''/home/ROBKRAUDY/test_1767558132566/tobiRecursive/functionsVAT/vatdef.pf.dds'') TOMBR(''/QSYS.lib/ROBKRAUDY1.lib/QPFSRC.file/VATDEF.mbr'') MBROPT(*REPLACE) CVTDTA(*AUTO) STMFCODPAG(1208) => CRTPF FILE(*CURLIB/VATDEF) SRCFILE(ROBKRAUDY1/QPFSRC) SRCMBR(VATDEF) OPTION(*EVENTF) => CPYFRMSTMF FROMSTMF(''/home/ROBKRAUDY/test_1767558132566/tobiRecursive/QDDSSRC/ARTICLE.pf.dds'') TOMBR(''/QSYS.lib/ROBKRAUDY1.lib/QPFSRC.file/ARTICLE.mbr'') MBROPT(*REPLACE) CVTDTA(*AUTO) STMFCODPAG(1208) => CRTPF FILE(*CURLIB/ARTICLE) SRCFILE(ROBKRAUDY1/QPFSRC) SRCMBR(ARTICLE) OPTION(*EVENTF) => CPYFRMSTMF FROMSTMF(''/home/ROBKRAUDY/test_1767558132566/tobiRecursive/QDDSSRC/ARTICLE1.lf.dds'') TOMBR(''/QSYS.lib/ROBKRAUDY1.lib/QLFSRC.file/ARTICLE1.mbr'') MBROPT(*REPLACE) CVTDTA(*AUTO) STMFCODPAG(1208) => CRTLF FILE(*CURLIB/ARTICLE1) SRCFILE(ROBKRAUDY1/QLFSRC) SRCMBR(ARTICLE1) OPTION(*EVENTF) => CPYFRMSTMF FROMSTMF(''/home/ROBKRAUDY/test_1767558132566/tobiRecursive/QDDSSRC/ARTICLE2.lf.dds'') TOMBR(''/QSYS.lib/ROBKRAUDY1.lib/QLFSRC.file/ARTICLE2.mbr'') MBROPT(*REPLACE) CVTDTA(*AUTO) STMFCODPAG(1208) => CRTLF FILE(*CURLIB/ARTICLE2) SRCFILE(ROBKRAUDY1/QLFSRC) SRCMBR(ARTICLE2) OPTION(*EVENTF) => CPYFRMSTMF FROMSTMF(''/home/ROBKRAUDY/test_1767558132566/tobiRecursive/QDDSSRC/ART200D.dspf.dds'') TOMBR(''/QSYS.lib/ROBKRAUDY1.lib/QDSPFSRC.file/ART200D.mbr'') MBROPT(*REPLACE) CVTDTA(*AUTO) STMFCODPAG(1208) => CRTDSPF FILE(*CURLIB/ART200D) SRCFILE(ROBKRAUDY1/QDSPFSRC) SRCMBR(ART200D) OPTION(*EVENTF) REPLACE(*YES) => CPYFRMSTMF FROMSTMF(''/home/ROBKRAUDY/test_1767558132566/tobiRecursive/QDDSSRC/FAMILLY.PF.dds'') TOMBR(''/QSYS.lib/ROBKRAUDY1.lib/QPFSRC.file/FAMILLY.mbr'') MBROPT(*REPLACE) CVTDTA(*AUTO) STMFCODPAG(1208) => CRTPF FILE(*CURLIB/FAMILLY) SRCFILE(ROBKRAUDY1/QPFSRC) SRCMBR(FAMILLY) OPTION(*EVENTF) => CPYFRMSTMF FROMSTMF(''/home/ROBKRAUDY/test_1767558132566/tobiRecursive/QDDSSRC/FAMILL1.LF.dds'') TOMBR(''/QSYS.lib/ROBKRAUDY1.lib/QLFSRC.file/FAMILL1.mbr'') MBROPT(*REPLACE) CVTDTA(*AUTO) STMFCODPAG(1208) => CRTLF FILE(*CURLIB/FAMILL1) SRCFILE(ROBKRAUDY1/QLFSRC) SRCMBR(FAMILL1) OPTION(*EVENTF) => CRTRPGMOD MODULE(*CURLIB/FAM300) SRCSTMF(''/home/ROBKRAUDY/test_1767558132566/tobiRecursive/QRPGLESRC/FAM300.module.RPGLE'') OPTION(*EVENTF) DBGVIEW(*ALL) REPLACE(*YES) TGTRLS(*CURRENT) TGTCCSID(*JOB) => CPYFRMSTMF FROMSTMF(''/home/ROBKRAUDY/test_1767558132566/tobiRecursive/QDDSSRC/FAM301D.DSPF.dds'') TOMBR(''/QSYS.lib/ROBKRAUDY1.lib/QDSPFSRC.file/FAM301D.mbr'') MBROPT(*REPLACE) CVTDTA(*AUTO) STMFCODPAG(1208) => CRTDSPF FILE(*CURLIB/FAM301D) SRCFILE(ROBKRAUDY1/QDSPFSRC) SRCMBR(FAM301D) OPTION(*EVENTF) REPLACE(*YES) => CRTRPGMOD MODULE(*CURLIB/FAM301) SRCSTMF(''/home/ROBKRAUDY/test_1767558132566/tobiRecursive/QRPGLESRC/FAM301.module.RPGLE'') OPTION(*EVENTF) DBGVIEW(*ALL) REPLACE(*YES) TGTRLS(*CURRENT) TGTCCSID(*JOB) => CRTSRVPGM SRVPGM(*CURLIB/FFAMILLY) MODULE(*LIBL/FAM300 *LIBL/FAM301) SRCSTMF(''/home/ROBKRAUDY/test_1767558132566/tobiRecursive/QSRVSRC/FFAMILLY.srvpgm.BND'') BNDSRVPGM(*NONE) OPTION(*EVENTF) REPLACE(*YES) TGTRLS(*CURRENT) => ADDBNDDIRE BNDDIR(*CURLIB/SAMPLE) OBJ(*LIBL/FFAMILLY) => CRTSQLRPGI OBJ(*CURLIB/ART200) SRCSTMF(''/home/ROBKRAUDY/test_1767558132566/tobiRecursive/QRPGLESRC/ART200.pgm.sqlrpgle'') COMMIT(*NONE) OBJTYPE(*PGM) OPTION(*EVENTF) TGTRLS(*CURRENT) REPLACE(*YES) DBGVIEW(*SOURCE) CVTCCSID(*JOB) => CPYFRMSTMF FROMSTMF(''/home/ROBKRAUDY/test_1767558132566/tobiRecursive/QDDSSRC/PROVIDER.PF.dds'') TOMBR(''/QSYS.lib/ROBKRAUDY1.lib/QPFSRC.file/PROVIDER.mbr'') MBROPT(*REPLACE) CVTDTA(*AUTO) STMFCODPAG(1208) => CRTPF FILE(*CURLIB/PROVIDER) SRCFILE(ROBKRAUDY1/QPFSRC) SRCMBR(PROVIDER) OPTION(*EVENTF) => CPYFRMSTMF FROMSTMF(''/home/ROBKRAUDY/test_1767558132566/tobiRecursive/QDDSSRC/PROVIDE1.LF.dds'') TOMBR(''/QSYS.lib/ROBKRAUDY1.lib/QLFSRC.file/PROVIDE1.mbr'') MBROPT(*REPLACE) CVTDTA(*AUTO) STMFCODPAG(1208) => CRTLF FILE(*CURLIB/PROVIDE1) SRCFILE(ROBKRAUDY1/QLFSRC) SRCMBR(PROVIDE1) OPTION(*EVENTF) => CPYFRMSTMF FROMSTMF(''/home/ROBKRAUDY/test_1767558132566/tobiRecursive/QDDSSRC/ARTIPROV.PF.dds'') TOMBR(''/QSYS.lib/ROBKRAUDY1.lib/QPFSRC.file/ARTIPROV.mbr'') MBROPT(*REPLACE) CVTDTA(*AUTO) STMFCODPAG(1208) => CRTPF FILE(*CURLIB/ARTIPROV) SRCFILE(ROBKRAUDY1/QPFSRC) SRCMBR(ARTIPROV) OPTION(*EVENTF) => CPYFRMSTMF FROMSTMF(''/home/ROBKRAUDY/test_1767558132566/tobiRecursive/QDDSSRC/ARTIPRO1.LF.dds'') TOMBR(''/QSYS.lib/ROBKRAUDY1.lib/QLFSRC.file/ARTIPRO1.mbr'') MBROPT(*REPLACE) CVTDTA(*AUTO) STMFCODPAG(1208) => CRTLF FILE(*CURLIB/ARTIPRO1) SRCFILE(ROBKRAUDY1/QLFSRC) SRCMBR(ARTIPRO1) OPTION(*EVENTF) => CPYFRMSTMF FROMSTMF(''/home/ROBKRAUDY/test_1767558132566/tobiRecursive/QDDSSRC/ART201D.dspf.dds'') TOMBR(''/QSYS.lib/ROBKRAUDY1.lib/QDSPFSRC.file/ART201D.mbr'') MBROPT(*REPLACE) CVTDTA(*AUTO) STMFCODPAG(1208) => CRTDSPF FILE(*CURLIB/ART201D) SRCFILE(ROBKRAUDY1/QDSPFSRC) SRCMBR(ART201D) OPTION(*EVENTF) REPLACE(*YES) => CRTRPGMOD MODULE(*CURLIB/ART300) SRCSTMF(''/home/ROBKRAUDY/test_1767558132566/tobiRecursive/QRPGLESRC/ART300.module.rpgle'') OPTION(*EVENTF) DBGVIEW(*ALL) REPLACE(*YES) TGTRLS(*CURRENT) TGTCCSID(*JOB) => CPYFRMSTMF FROMSTMF(''/home/ROBKRAUDY/test_1767558132566/tobiRecursive/QDDSSRC/ART301D.dspf.dds'') TOMBR(''/QSYS.lib/ROBKRAUDY1.lib/QDSPFSRC.file/ART301D.mbr'') MBROPT(*REPLACE) CVTDTA(*AUTO) STMFCODPAG(1208) => CRTDSPF FILE(*CURLIB/ART301D) SRCFILE(ROBKRAUDY1/QDSPFSRC) SRCMBR(ART301D) OPTION(*EVENTF) REPLACE(*YES) => CRTSQLRPGI OBJ(*CURLIB/ART301) SRCSTMF(''/home/ROBKRAUDY/test_1767558132566/tobiRecursive/QRPGLESRC/ART301.module.SQLRPGLE'') COMMIT(*NONE) OBJTYPE(*MODULE) OPTION(*EVENTF) TGTRLS(*CURRENT) REPLACE(*YES) DBGVIEW(*SOURCE) CVTCCSID(*JOB) => CRTSRVPGM SRVPGM(*CURLIB/FARTICLE) MODULE(*LIBL/ART300 *LIBL/ART301) SRCSTMF(''/home/ROBKRAUDY/test_1767558132566/tobiRecursive/QSRVSRC/FARTICLE.srvpgm.BND'') TEXT(''Function Article'') BNDSRVPGM(FFAMILLY) OPTION(*EVENTF) REPLACE(*YES) TGTRLS(*CURRENT) => CRTRPGMOD MODULE(*CURLIB/PRO300) SRCSTMF(''/home/ROBKRAUDY/test_1767558132566/tobiRecursive/QRPGLESRC/PRO300.module.RPGLE'') OPTION(*EVENTF) DBGVIEW(*ALL) REPLACE(*YES) TGTRLS(*CURRENT) TGTCCSID(*JOB) => CRTSRVPGM SRVPGM(*CURLIB/FPROVIDER) MODULE(*LIBL/PRO300) SRCSTMF(''/home/ROBKRAUDY/test_1767558132566/tobiRecursive/QSRVSRC/FPROVIDER.srvpgm.BND'') TEXT(''Functions Provider'') BNDSRVPGM(*NONE) OPTION(*EVENTF) REPLACE(*YES) TGTRLS(*CURRENT) => ADDBNDDIRE BNDDIR(*CURLIB/SAMPLE) OBJ(*LIBL/FARTICLE *LIBL/FPROVIDER) => CRTBNDRPG PGM(*CURLIB/ART201) SRCSTMF(''/home/ROBKRAUDY/test_1767558132566/tobiRecursive/QRPGLESRC/ART201.pgm.rpgle'') OPTION(*EVENTF) DBGVIEW(*ALL) REPLACE(*YES) TGTRLS(*CURRENT) TGTCCSID(*JOB) => CPYFRMSTMF FROMSTMF(''/home/ROBKRAUDY/test_1767558132566/tobiRecursive/QDDSSRC/ART202D.dspf.dds'') TOMBR(''/QSYS.lib/ROBKRAUDY1.lib/QDSPFSRC.file/ART202D.mbr'') MBROPT(*REPLACE) CVTDTA(*AUTO) STMFCODPAG(1208) => CRTDSPF FILE(*CURLIB/ART202D) SRCFILE(ROBKRAUDY1/QDSPFSRC) SRCMBR(ART202D) OPTION(*EVENTF) REPLACE(*YES) => CRTBNDRPG PGM(*CURLIB/ART202) SRCSTMF(''/home/ROBKRAUDY/test_1767558132566/tobiRecursive/QRPGLESRC/ART202.PGM.RPGLE'') OPTION(*EVENTF) DBGVIEW(*ALL) REPLACE(*YES) TGTRLS(*CURRENT) TGTCCSID(*JOB) => CPYFRMSTMF FROMSTMF(''/home/ROBKRAUDY/test_1767558132566/tobiRecursive/QDDSSRC/PARAMETER.PF.dds'') TOMBR(''/QSYS.lib/ROBKRAUDY1.lib/QPFSRC.file/PARAMETER.mbr'') MBROPT(*REPLACE) CVTDTA(*AUTO) STMFCODPAG(1208) => CRTPF FILE(*CURLIB/PARAMETER) SRCFILE(ROBKRAUDY1/QPFSRC) SRCMBR(PARAMETER) OPTION(*EVENTF) => CRTBNDRPG PGM(*CURLIB/LOG100) SRCSTMF(''/home/ROBKRAUDY/test_1767558132566/tobiRecursive/QRPGLESRC/LOG100.PGM.RPGLE'') OPTION(*EVENTF) DBGVIEW(*ALL) REPLACE(*YES) TGTRLS(*CURRENT) TGTCCSID(*JOB) => CPYFRMSTMF FROMSTMF(''/home/ROBKRAUDY/test_1767558132566/tobiRecursive/QDDSSRC/DETORD.PF.dds'') TOMBR(''/QSYS.lib/ROBKRAUDY1.lib/QPFSRC.file/DETORD.mbr'') MBROPT(*REPLACE) CVTDTA(*AUTO) STMFCODPAG(1208) => CRTPF FILE(*CURLIB/DETORD) SRCFILE(ROBKRAUDY1/QPFSRC) SRCMBR(DETORD) OPTION(*EVENTF) => CPYFRMSTMF FROMSTMF(''/home/ROBKRAUDY/test_1767558132566/tobiRecursive/QDDSSRC/DETORD1.LF.dds'') TOMBR(''/QSYS.lib/ROBKRAUDY1.lib/QLFSRC.file/DETORD1.mbr'') MBROPT(*REPLACE) CVTDTA(*AUTO) STMFCODPAG(1208) => CRTLF FILE(*CURLIB/DETORD1) SRCFILE(ROBKRAUDY1/QLFSRC) SRCMBR(DETORD1) OPTION(*EVENTF) => CPYFRMSTMF FROMSTMF(''/home/ROBKRAUDY/test_1767558132566/tobiRecursive/QDDSSRC/ORDER.PF.dds'') TOMBR(''/QSYS.lib/ROBKRAUDY1.lib/QPFSRC.file/ORDER.mbr'') MBROPT(*REPLACE) CVTDTA(*AUTO) STMFCODPAG(1208) => CRTPF FILE(*CURLIB/ORDER) SRCFILE(ROBKRAUDY1/QPFSRC) SRCMBR(ORDER) OPTION(*EVENTF) => CPYFRMSTMF FROMSTMF(''/home/ROBKRAUDY/test_1767558132566/tobiRecursive/QDDSSRC/ORDER3.LF.dds'') TOMBR(''/QSYS.lib/ROBKRAUDY1.lib/QLFSRC.file/ORDER3.mbr'') MBROPT(*REPLACE) CVTDTA(*AUTO) STMFCODPAG(1208) => CRTLF FILE(*CURLIB/ORDER3) SRCFILE(ROBKRAUDY1/QLFSRC) SRCMBR(ORDER3) OPTION(*EVENTF) => CPYFRMSTMF FROMSTMF(''/home/ROBKRAUDY/test_1767558132566/tobiRecursive/QDDSSRC/ORD100D.DSPF.dds'') TOMBR(''/QSYS.lib/ROBKRAUDY1.lib/QDSPFSRC.file/ORD100D.mbr'') MBROPT(*REPLACE) CVTDTA(*AUTO) STMFCODPAG(1208) => CRTDSPF FILE(*CURLIB/ORD100D) SRCFILE(ROBKRAUDY1/QDSPFSRC) SRCMBR(ORD100D) OPTION(*EVENTF) REPLACE(*YES) => CRTRPGMOD MODULE(*CURLIB/VAT300) SRCSTMF(''/home/ROBKRAUDY/test_1767558132566/tobiRecursive/functionsVAT/vat300.module.rpgle'') OPTION(*EVENTF) DBGVIEW(*ALL) REPLACE(*YES) TGTRLS(*CURRENT) TGTCCSID(*JOB) => CRTSRVPGM SRVPGM(*CURLIB/FVAT) MODULE(*LIBL/VAT300) SRCSTMF(''/home/ROBKRAUDY/test_1767558132566/tobiRecursive/functionsVAT/fvat.srvpgm.bnd'') TEXT(''Functions VAT'') BNDSRVPGM(*NONE) OPTION(*EVENTF) REPLACE(*YES) TGTRLS(*CURRENT) => CPYFRMSTMF FROMSTMF(''/home/ROBKRAUDY/test_1767558132566/tobiRecursive/QDDSSRC/CUSTOMER.PF.dds'') TOMBR(''/QSYS.lib/ROBKRAUDY1.lib/QPFSRC.file/CUSTOMER.mbr'') MBROPT(*REPLACE) CVTDTA(*AUTO) STMFCODPAG(1208) => CRTPF FILE(*CURLIB/CUSTOMER) SRCFILE(ROBKRAUDY1/QPFSRC) SRCMBR(CUSTOMER) OPTION(*EVENTF) => CPYFRMSTMF FROMSTMF(''/home/ROBKRAUDY/test_1767558132566/tobiRecursive/QDDSSRC/CUSTOME1.LF.dds'') TOMBR(''/QSYS.lib/ROBKRAUDY1.lib/QLFSRC.file/CUSTOME1.mbr'') MBROPT(*REPLACE) CVTDTA(*AUTO) STMFCODPAG(1208) => CRTLF FILE(*CURLIB/CUSTOME1) SRCFILE(ROBKRAUDY1/QLFSRC) SRCMBR(CUSTOME1) OPTION(*EVENTF) => CRTRPGMOD MODULE(*CURLIB/CUS300) SRCSTMF(''/home/ROBKRAUDY/test_1767558132566/tobiRecursive/QRPGLESRC/CUS300.module.RPGLE'') OPTION(*EVENTF) DBGVIEW(*ALL) REPLACE(*YES) TGTRLS(*CURRENT) TGTCCSID(*JOB) => CPYFRMSTMF FROMSTMF(''/home/ROBKRAUDY/test_1767558132566/tobiRecursive/QDDSSRC/CUS301D.dspf.dds'') TOMBR(''/QSYS.lib/ROBKRAUDY1.lib/QDSPFSRC.file/CUS301D.mbr'') MBROPT(*REPLACE) CVTDTA(*AUTO) STMFCODPAG(1208) => CRTDSPF FILE(*CURLIB/CUS301D) SRCFILE(ROBKRAUDY1/QDSPFSRC) SRCMBR(CUS301D) OPTION(*EVENTF) REPLACE(*YES) => CRTSQLRPGI OBJ(*CURLIB/CUS301) SRCSTMF(''/home/ROBKRAUDY/test_1767558132566/tobiRecursive/QRPGLESRC/CUS301.module.SQLRPGLE'') COMMIT(*NONE) OBJTYPE(*MODULE) OPTION(*EVENTF) TGTRLS(*CURRENT) REPLACE(*YES) DBGVIEW(*SOURCE) CVTCCSID(*JOB) => CRTSRVPGM SRVPGM(*CURLIB/FCUSTOMER) MODULE(*LIBL/CUS300 *LIBL/CUS301) SRCSTMF(''/home/ROBKRAUDY/test_1767558132566/tobiRecursive/QSRVSRC/FCUSTOMER.srvpgm.BND'') TEXT(''Functions Customer'') BNDSRVPGM(*NONE) OPTION(*EVENTF) REPLACE(*YES) TGTRLS(*CURRENT) => OVRDBF FILE(tmpdetord) TOFILE(*LIBL/detord) OVRSCOPE(*JOB) => ADDBNDDIRE BNDDIR(*CURLIB/SAMPLE) OBJ(*LIBL/fvat *LIBL/FCUSTOMER) => CRTBNDRPG PGM(*CURLIB/ORD100) SRCSTMF(''/home/ROBKRAUDY/test_1767558132566/tobiRecursive/QRPGLESRC/ORD100.PGM.RPGLE'') OPTION(*EVENTF) DBGVIEW(*ALL) REPLACE(*YES) TGTRLS(*CURRENT) TGTCCSID(*JOB) => DLTOVR FILE(tmpdetord) LVL(*JOB) => CPYFRMSTMF FROMSTMF(''/home/ROBKRAUDY/test_1767558132566/tobiRecursive/QDDSSRC/ORDER1.LF.dds'') TOMBR(''/QSYS.lib/ROBKRAUDY1.lib/QLFSRC.file/ORDER1.mbr'') MBROPT(*REPLACE) CVTDTA(*AUTO) STMFCODPAG(1208) => CRTLF FILE(*CURLIB/ORDER1) SRCFILE(ROBKRAUDY1/QLFSRC) SRCMBR(ORDER1) OPTION(*EVENTF) => ADDPFM FILE(ROBKRAUDY1/QDSPFSRC) MBR(ORD101D) SRCTYPE(DDS) => CPYFRMSTMF FROMSTMF(''/home/ROBKRAUDY/test_1767558132566/tobiRecursive/QDDSSRC/ORD101D.DSPF.dds'') TOMBR(''/QSYS.lib/ROBKRAUDY1.lib/QDSPFSRC.file/ORD101D.mbr'') MBROPT(*REPLACE) CVTDTA(*AUTO) STMFCODPAG(1208) => CRTDSPF FILE(*CURLIB/ORD101D) SRCFILE(ROBKRAUDY1/QDSPFSRC) SRCMBR(ORD101D) OPTION(*EVENTF) REPLACE(*YES) => CRTBNDRPG PGM(*CURLIB/ORD101) SRCSTMF(''/home/ROBKRAUDY/test_1767558132566/tobiRecursive/QRPGLESRC/ORD101.PGM.RPGLE'') OPTION(*EVENTF) DBGVIEW(*ALL) REPLACE(*YES) TGTRLS(*CURRENT) TGTCCSID(*JOB) => DLTOBJ OBJ(*LIBL/SAMPLE) OBJTYPE(*BNDDIR)
+14:30:14.754 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: CHGCURDIR DIR(''/home/ROBKRAUDY'')
+14:30:43.011 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: RMVDIR DIR(''/home/ROBKRAUDY/test_1767558132566'') SUBTREE(*ALL)
+14:30:44.147 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: DLTOBJ OBJ(*CURLIB/ORD101) OBJTYPE(*PGM)
+14:30:45.339 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: DLTOBJ OBJ(*CURLIB/ORDER1) OBJTYPE(*FILE)
+14:30:46.488 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: DLTOBJ OBJ(*CURLIB/ORD100) OBJTYPE(*PGM)
+14:30:47.642 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: DLTOBJ OBJ(*CURLIB/FCUSTOMER) OBJTYPE(*SRVPGM)
+14:30:48.757 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: DLTOBJ OBJ(*CURLIB/CUS301) OBJTYPE(*MODULE)
+14:30:49.882 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: DLTOBJ OBJ(*CURLIB/CUS300) OBJTYPE(*MODULE)
+14:30:51.049 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: DLTOBJ OBJ(*CURLIB/CUSTOME1) OBJTYPE(*FILE)
14:37:17.541 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: DLTOBJ OBJ(*CURLIB/CUSTOMER) OBJTYPE(*FILE)
+14:30:53.354 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: DLTOBJ OBJ(*CURLIB/FVAT) OBJTYPE(*SRVPGM)
+14:30:54.504 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: DLTOBJ OBJ(*CURLIB/VAT300) OBJTYPE(*MODULE)
+14:30:55.627 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: DLTOBJ OBJ(*CURLIB/ORDER3) OBJTYPE(*FILE)
+14:30:56.789 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: DLTOBJ OBJ(*CURLIB/ORDER) OBJTYPE(*FILE)
+14:30:57.956 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: DLTOBJ OBJ(*CURLIB/DETORD1) OBJTYPE(*FILE)
+14:30:59.103 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: DLTOBJ OBJ(*CURLIB/DETORD) OBJTYPE(*FILE)
+ 14:31:00.260 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: DLTOBJ OBJ(*CURLIB/LOG100) OBJTYPE(*PGM)
+ 14:31:01.403 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: DLTOBJ OBJ(*CURLIB/PARAMETER) OBJTYPE(*FILE)
+ 14:31:02.509 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: DLTOBJ OBJ(*CURLIB/ART202) OBJTYPE(*PGM)
+ 14:31:03.616 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: DLTOBJ OBJ(*CURLIB/ART201) OBJTYPE(*PGM)
+ 14:31:04.728 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: DLTOBJ OBJ(*CURLIB/FPROVIDER) OBJTYPE(*SRVPGM)
+ 14:31:05.843 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: DLTOBJ OBJ(*CURLIB/PRO300) OBJTYPE(*MODULE)
+ 14:31:06.985 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: DLTOBJ OBJ(*CURLIB/FARTICLE) OBJTYPE(*SRVPGM)
+ 14:31:08.095 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: DLTOBJ OBJ(*CURLIB/ART301) OBJTYPE(*MODULE)
+ 14:31:09.215 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: DLTOBJ OBJ(*CURLIB/ART300) OBJTYPE(*MODULE)
+ 14:31:10.389 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: DLTOBJ OBJ(*CURLIB/ARTIPRO1) OBJTYPE(*FILE)
+ 14:31:11.545 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: DLTOBJ OBJ(*CURLIB/ARTIPROV) OBJTYPE(*FILE)
+ 14:31:12.706 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: DLTOBJ OBJ(*CURLIB/PROVIDE1) OBJTYPE(*FILE)
+ 14:31:13.864 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: DLTOBJ OBJ(*CURLIB/PROVIDER) OBJTYPE(*FILE)
+ 14:31:15.021 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: DLTOBJ OBJ(*CURLIB/ART200) OBJTYPE(*PGM)
+ 14:31:16.171 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: DLTOBJ OBJ(*CURLIB/FFAMILLY) OBJTYPE(*SRVPGM)
+ 14:31:17.314 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: DLTOBJ OBJ(*CURLIB/FAM301) OBJTYPE(*MODULE)
+ 14:31:18.467 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: DLTOBJ OBJ(*CURLIB/FAM300) OBJTYPE(*MODULE)
+ 14:31:19.631 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: DLTOBJ OBJ(*CURLIB/FAMILL1) OBJTYPE(*FILE)
+ 14:31:20.806 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: DLTOBJ OBJ(*CURLIB/FAMILLY) OBJTYPE(*FILE)
+ 14:31:21.978 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: DLTOBJ OBJ(*CURLIB/ARTICLE2) OBJTYPE(*FILE)
+ 14:31:23.130 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: DLTOBJ OBJ(*CURLIB/ARTICLE1) OBJTYPE(*FILE)
+ 14:31:24.253 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: DLTOBJ OBJ(*CURLIB/ARTICLE) OBJTYPE(*FILE)
+ 14:31:25.420 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: DLTOBJ OBJ(*CURLIB/VATDEF) OBJTYPE(*FILE)
+ 14:31:27.600 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: DLTOBJ OBJ(*CURLIB/SAMREF) OBJTYPE(*FILE)
+ 14:31:28.745 [main] INFO  c.g.kraudy.compiler.CommandExecutor - 
Command successful: DLTOBJ OBJ(*CURLIB/ADDNUM) OBJTYPE(*PGM)
```
