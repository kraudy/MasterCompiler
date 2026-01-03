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
  static private CommandExecutor commandExecutor;

  @BeforeAll
  static void setupSystem() throws Exception {
    system = IBMiDotEnv.getNewSystemConnection(true);
    connection = new AS400JDBCDataSource(system).getConnection();
    currentUser = new User(system, system.getUserId());
    currentUser.loadUserInformation();
    commandExecutor = new CommandExecutor(connection, false, false, false);

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

    /* Clean library list for tests */
    CommandObject chgLibl = new CommandObject(SysCmd.CHGLIBL)
      .put(ParamCmd.LIBL, curlib);

    commandExecutor.executeCommand(chgLibl);

  }

  @AfterAll
  static void teardown() throws Exception {
    if (connection != null) connection.close();
    if (system != null) system.disconnectAllServices();
  }

  @Test
  void test_Full_Ile_Compilation_Flow() throws Exception {
    /* Load sources per key */
    Map<TargetKey, String> keyMap = Map.of(
        new TargetKey("curlib.hello.module.rpgle"),   "sources/rpgle/hello.module.rpgle",
        new TargetKey("curlib.bye.module.rpgle"),     "sources/rpgle/bye.module.rpgle",
        new TargetKey("curlib.srvhello.srvpgm.bnd"),   "sources/rpgle/srvhello.srvpgm.bnd",
        new TargetKey("curlib.hello.pgm.rpgle"),      "sources/rpgle/hello.pgm.rpgle"

    );

    masterCompilerTest(
        "yaml/integration/multi/multi.hello.pgm.rpgle.yaml",
        keyMap
    );
  }

  private void masterCompilerTest(String yamlResourcePath, Map<TargetKey, String> keyMap) throws Exception {
    // 1. Load and deserialize the base YAML
    String yamlContent = TestHelpers.loadResourceAsString(yamlResourcePath);

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

        // Get resource path
        String sourceCode = TestHelpers.loadResourceAsString(keyMap.get(key));

        // Create unique IFS path
        String ifsPath = currentUser.getHomeDirectory() + "/" +
                System.currentTimeMillis() + "_" + key.asFileName();

        /* Create source on remote server */
        IFSFile ifsFile = new IFSFile(system, ifsPath);
        ifsFile.createNewFile();
        try (IFSFileWriter writer = new IFSFileWriter(ifsFile, false)) {
          writer.write(sourceCode);
        }

        /* Inject into the target's params. This even works for opm or any other object */
        targetSpec.params.put(ParamCmd.SRCSTMF, ifsPath);

        /* Track paths and objects for deletion */
        ifsPathsToDelete.add(ifsPath);
        objectsToDelete.add(key);

        // targetSpec.params.put(ParamCmd.TEXT, "Integration test - " + key.getObjectName());
      }

      // 4. Run the compiler
      MasterCompiler compiler = new MasterCompiler(
              system, connection, spec,
              false, true, true, false, false
      );

      compiler.build();

      assertFalse(compiler.foundCompilationError(), "Test compilation failed");

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
          CommandObject dlt = new CommandObject(SysCmd.DLTOBJ)
            .put(ParamCmd.OBJ, key.getQualifiedObject(ValCmd.CURLIB))
            .put(ParamCmd.OBJTYPE, ValCmd.fromString(key.getObjectType()));

          commandExecutor.executeCommand(dlt);
        } catch (Exception ignored) {}  // This prevents breaking the loop
      }
    }
  }

}
