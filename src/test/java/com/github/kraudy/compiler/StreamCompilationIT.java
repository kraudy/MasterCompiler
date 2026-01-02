package com.github.kraudy.compiler;

import com.github.kraudy.compiler.CompilationPattern.ParamCmd;
import com.github.kraudy.compiler.CompilationPattern.SysCmd;
import com.github.kraudy.compiler.CompilationPattern.ValCmd;
import com.ibm.as400.access.AS400;
import com.ibm.as400.access.AS400JDBCDataSource;
import com.ibm.as400.access.IFSFile;
import com.ibm.as400.access.IFSFileWriter;
import com.ibm.as400.access.User;

import io.github.theprez.dotenv_ibmi.IBMiDotEnv;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
@DisplayName("Full Compilation Integration Test using IFS Stream File")
public class StreamCompilationIT {
  static private AS400 system;
  static private Connection connection;
  static private User currentUser;
  static private String curlib;
  private IFSFile ifsFile;

  @BeforeAll
  static void setupSystem() throws Exception {
    system = IBMiDotEnv.getNewSystemConnection(true);
    connection = new AS400JDBCDataSource(system).getConnection();
    currentUser = new User(system, system.getUserId());
    currentUser.loadUserInformation();

    try(Statement stmt = connection.createStatement();
        ResultSet rsCurLib = stmt.executeQuery(
          "SELECT TRIM(SCHEMA_NAME) As SCHEMA_NAME FROM QSYS2.LIBRARY_LIST_INFO WHERE TYPE = 'CURRENT'" 
        )){
      if (!rsCurLib.next()) {
        throw new CompilerException("Error retrieving current library");
      }
      curlib = rsCurLib.getString("SCHEMA_NAME");

    } catch (SQLException e){
      throw new CompilerException("Error retrieving current library", e);
    }

  }

  @AfterAll
  static void teardown() throws Exception {
    if (connection != null) connection.close();
    if (system != null) system.disconnectAllServices();
  }

  @Test
    void test_Unit_Compile_Pgm_Rpgle_Streamfile() throws Exception {
      compileFromStreamFile(
          "curlib.HELLO.pgm.rpgle",
          "sources/rpgle/hello.pgm.rpgle",
          "yaml/integration/unit/hello.pgm.rpgle.yaml"
      );
    }

  @Test
  void test_Unit_Compile_Module_Rpgle_Streamfile() throws Exception {
    compileFromStreamFile(
        "curlib.HELLO.module.rpgle",
        "sources/rpgle/hello.module.rpgle",
        "yaml/integration/unit/hello.module.rpgle.yaml"
    );

    compileFromStreamFile(
        "curlib.bye.module.rpgle",
        "sources/rpgle/bye.module.rpgle",
        "yaml/integration/unit/bye.module.rpgle.yaml"
    );
  }

  @Test
  void test_Multi_Target_With_Predefined_Params_And_Empty_Targets() throws Exception {
    /* Load sources per key */
    //TODO: Make this a list of TargetKeys and set the source with String setResourcePath; // Used to store source for test.
    Map<String, String> sourcesByTarget = Map.of(
        "curlib.hello.module.rpgle",   "sources/rpgle/hello.module.rpgle",
        "curlib.bye.module.rpgle",     "sources/rpgle/bye.module.rpgle",
        "curlib.hello.pgm.rpgle",      "sources/rpgle/hello.pgm.rpgle"
    );

    compileMultiTargetYaml(
        "yaml/integration/multi/multi.hello.pgm.rpgle.yaml",
        sourcesByTarget
    );
  }

  private void compileMultiTargetYaml(String yamlResourcePath,
                                    Map<String, String> sourceResourceByTarget) throws Exception {
    // 1. Load and deserialize the base YAML
    String yamlContent = TestHelpers.loadResourceAsString(yamlResourcePath)
              .replace("${CURLIB}", curlib);

    Path tempYaml = Files.createTempFile("multi-", ".yaml");
    Files.writeString(tempYaml, yamlContent);

    BuildSpec spec = Utilities.deserializeYaml(tempYaml.toString());

    // 2. Map to hold IFS paths for cleanup
    List<String> ifsPathsToDelete = new ArrayList<>();
    List<TargetKey> objectsToDelete = new ArrayList<>();

    try {
      // 3. Iterate over targets and inject dynamic values
      for (Map.Entry<TargetKey, BuildSpec.TargetSpec> entry : spec.targets.entrySet()) {
        TargetKey key = entry.getKey();
        BuildSpec.TargetSpec targetSpec = entry.getValue();

        //TODO: Improve this. Add methos isContained() to TargetKey that does this lookup and uses its internal equals override
        String targetKeyStr = key.asString().toLowerCase(); // normalize for lookup

        // Only process targets that need a stream file
        if (sourceResourceByTarget.containsKey(targetKeyStr)) {
          String sourceResource = sourceResourceByTarget.get(targetKeyStr);
          String sourceCode = TestHelpers.loadResourceAsString(sourceResource);

          // Create unique IFS path
          String ifsPath = currentUser.getHomeDirectory() + "/" +
                  System.currentTimeMillis() + "_" + key.asFileName();

          /* Create source on remote server */
          IFSFile ifsFile = new IFSFile(system, ifsPath);
          ifsFile.createNewFile();
          try (IFSFileWriter writer = new IFSFileWriter(ifsFile, false)) {
            writer.write(sourceCode);
          }

          // Inject into the target's params
          targetSpec.params.put(ParamCmd.SRCSTMF, ifsPath);

          /* Track paths and objects for deletion */
          ifsPathsToDelete.add(ifsPath);
          objectsToDelete.add(key);
        }

        // You can also inject other dynamic params here, e.g.:
        // targetSpec.params.put(ParamCmd.TEXT, "Integration test - " + key.getObjectName());
      }

      // 4. Run the compiler
      MasterCompiler compiler = new MasterCompiler(
              system, connection, spec,
              false, true, true, false, false
      );
      compiler.build();

      assertFalse(compiler.foundCompilationError(), "Multi-target compilation failed");

    } finally {
        // 5. Cleanup
        Files.deleteIfExists(tempYaml);

        // Delete IFS files
        for (String path : ifsPathsToDelete) {
            IFSFile file = new IFSFile(system, path);
            if (file.exists()) file.delete();
        }

        // Delete compiled objects (optional but nice)
        for (TargetKey key : objectsToDelete) {
            try {
                CommandObject dlt = new CommandObject(SysCmd.DLTOBJ);
                dlt.put(ParamCmd.OBJ, key.getQualifiedObject(ValCmd.CURLIB));
                dlt.put(ParamCmd.OBJTYPE, ValCmd.fromString(key.getObjectType()));
                new CommandExecutor(connection, false, false, false)
                        .executeCommand(dlt);
            } catch (Exception ignored) {}
        }
    }
  }

  private void compileFromStreamFile(
          String targetKeyStr,
          String sourceResourcePath,
          String yamlResourcePath) throws Exception {

    TargetKey key = new TargetKey(targetKeyStr);

    // Load source
    String sourceCode = TestHelpers.loadResourceAsString(sourceResourcePath);

    // Create unique IFS path
    String ifsPath = currentUser.getHomeDirectory() + "/" +
                      System.currentTimeMillis() + "_" + key.getObjectName() + "." + key.getSourceType();

    IFSFile ifsFile = new IFSFile(system, ifsPath);
    ifsFile.createNewFile();

    try (IFSFileWriter writer = new IFSFileWriter(ifsFile, false)) {
        writer.write(sourceCode);
    }

    // Set stream file in key
    key.setStreamSourceFile(ifsPath);

    // Load and customize YAML
    String yamlContent = TestHelpers.loadResourceAsString(yamlResourcePath)
            .replace("${CURLIB}", curlib)
            .replace("${OBJECT_NAME}", key.getObjectName())
            .replace("${SRCSTMF}", ifsPath);

    Path tempYaml = Files.createTempFile("test-", ".yaml");
    Files.writeString(tempYaml, yamlContent);

    try {
        BuildSpec spec = Utilities.deserializeYaml(tempYaml.toString());

        MasterCompiler compiler = new MasterCompiler(
                system,
                connection,
                spec,
                false,  // dryRun
                true,   // debug
                true,   // verbose
                false,  // diff
                false   // noMigrate
        );

        compiler.build();

        assertFalse(compiler.foundCompilationError(),
                "Compilation should succeed for " + targetKeyStr);

    } finally {
        Files.deleteIfExists(tempYaml);
        if (ifsFile.exists()) {
            ifsFile.delete();
        }
    }
  }

}
