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

    masterCompilerTest(
        "yaml/integration/multi/multi.hello.pgm.rpgle.yaml"
    );
  }

  private void masterCompilerTest(String yamlResourcePath) throws Exception {
    // 1. Load and deserialize the base YAML
    String yamlContent = TestHelpers.loadResourceAsString(yamlResourcePath);

    Path tempYaml = Files.createTempFile("multi-", ".yaml");
    Files.writeString(tempYaml, yamlContent);

    BuildSpec spec = Utilities.deserializeYaml(tempYaml.toString());

    // 2. Map to hold IFS paths for cleanup
    List<String> ifsPathsToDelete = new ArrayList<>();
    List<TargetKey> objectsToDelete = new ArrayList<>();

    String testFolder = currentUser.getHomeDirectory() + "/" + "test_" + System.currentTimeMillis();

    try {
      // 3. Iterate over targets and inject dynamic values
      for (Map.Entry<TargetKey, BuildSpec.TargetSpec> entry : spec.targets.entrySet()) {
        TargetKey key = entry.getKey();
        BuildSpec.TargetSpec targetSpec = entry.getValue();

        /* Get source stream file */
        String relativeSrc = targetSpec.params.get(ParamCmd.SRCSTMF);
        // Get resource path
        String sourceCode = TestHelpers.loadResourceAsString(relativeSrc);

        /* Append home dir + timestamp for file */
        String remotePath = testFolder + "/" + relativeSrc;

        // Create parent directories recursively
        IFSFile parentDir = new IFSFile(system, new IFSFile(system, remotePath).getParent());
        if (!parentDir.exists()) {
          parentDir.mkdirs();  // This creates the full chain (e.g., sources/rpgle)
        }

        /* Create source on remote server */
        IFSFile remoteFile = new IFSFile(system, remotePath);
        remoteFile.createNewFile();
        try (IFSFileWriter writer = new IFSFileWriter(remoteFile, false)) {
          writer.write(sourceCode);
        }

        /* Inject into the target's params. This even works for opm or any other object */
        targetSpec.params.put(ParamCmd.SRCSTMF, remotePath);  // Replace with absolute

        ifsPathsToDelete.add(remotePath);

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

      CommandObject rmvDir = new CommandObject(SysCmd.RMVDIR)
        .put(ParamCmd.DIR, testFolder)
        .put(ParamCmd.SUBTREE, ValCmd.ALL);

      commandExecutor.executeCommand(rmvDir);

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
