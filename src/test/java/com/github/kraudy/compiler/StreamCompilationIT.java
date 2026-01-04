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

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

  //@Test
  void test_Full_Ile_Compilation_Flow() throws Exception {    

    masterCompilerTest(
        "yaml/integration/multi/multi.hello.pgm.rpgle.yaml"
    );
  }

  @Test
  void test_Tobi_Bob() throws Exception {    

    masterCompilerTest("tobiRecursive/tobi.yaml");
    
  }

  private void masterCompilerTest(String yamlResourcePath) throws Exception {
    boolean errorFound = false;

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
      /* Iterate over targets and inject dynamic values */
      for (Map.Entry<TargetKey, BuildSpec.TargetSpec> entry : spec.targets.entrySet()) {
        TargetKey key = entry.getKey();
        BuildSpec.TargetSpec targetSpec = entry.getValue();

        /* Get source stream file */
        String relativeSrc = targetSpec.params.get(ParamCmd.SRCSTMF);
        // Get resource path
        System.out.println("Loading source code: " + relativeSrc);
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
          System.out.println("Uploading source code: " + remotePath);
          writer.write(sourceCode);
        }

        /* Inject into the target's params. This even works for opm or any other object */
        targetSpec.params.put(ParamCmd.SRCSTMF, remotePath);  // Replace with absolute

        ifsPathsToDelete.add(remotePath);

        objectsToDelete.add(key);

        // targetSpec.params.put(ParamCmd.TEXT, "Integration test - " + key.getObjectName());

        /* Resolve copy/include */
        Pattern copyPattern = Pattern.compile("\\s*/(?:COPY|INCLUDE)\\s+['\"]?([^'\";\\s]+)['\"]?", Pattern.CASE_INSENSITIVE);
        Matcher matcher = copyPattern.matcher(sourceCode);

        while (matcher.find()) {
          System.out.println("Found copy/include directive in: " + relativeSrc);
          String includePath = matcher.group(1).trim();  // e.g., "../QPROTOSRC/familly.RPGLEINC"

          System.out.println("Loading copy/include source: " + includePath);
          String includeCode = TestHelpers.loadResourceAsString(includePath);

          String remoteIncludePath = testFolder + "/" + includePath;

          // Create parent dirs for include
          // Create parent dirs
          String parentPath = new IFSFile(system, remoteIncludePath).getParent();
          IFSFile incParent = new IFSFile(system, parentPath);
          if (!incParent.exists()) {
              incParent.mkdirs();
          }

          // Upload include
          IFSFile incFile = new IFSFile(system, remoteIncludePath);
          if (!incFile.exists()) {
            incFile.createNewFile();
          }
          try (IFSFileWriter w = new IFSFileWriter(incFile, false)) {
            System.out.println("Uploading include: " + remoteIncludePath);
            w.write(includeCode);
          }

          ifsPathsToDelete.add(remoteIncludePath);
        }

      }

      /* Change cur dir to test dir */
      CommandObject chgCurDir = new CommandObject(SysCmd.CHGCURDIR)
        .put(ParamCmd.DIR, testFolder);
      commandExecutor.executeCommand(chgCurDir);

      /* Run the compiler */
      MasterCompiler compiler = new MasterCompiler(
              system, connection, spec,
              false, true, true, false, false
      );

      compiler.build();

      errorFound = compiler.foundCompilationError();

      /* Set cur dir back */
      CommandObject chgHome = new CommandObject(SysCmd.CHGCURDIR)
        .put(ParamCmd.DIR, currentUser.getHomeDirectory());
      commandExecutor.executeCommand(chgHome);

    } finally {
      // 5. Cleanup
      Files.deleteIfExists(tempYaml);

      // Delete IFS files
      for (String path : ifsPathsToDelete) {
        IFSFile file = new IFSFile(system, path);
        if (file.exists()) file.delete();
      }

      try {
        CommandObject rmvDir = new CommandObject(SysCmd.RMVDIR)
          .put(ParamCmd.DIR, testFolder)
          .put(ParamCmd.SUBTREE, ValCmd.ALL);

        commandExecutor.executeCommand(rmvDir);
      } catch (CompilerException e) {
        System.out.println(e.getFullContext());
      }

      // Delete compiled objects in reverse creation order (dependents first)
      for (int i = objectsToDelete.size() - 1; i >= 0; i--) {
        TargetKey key = objectsToDelete.get(i);
        try {
          CommandObject dlt = new CommandObject(SysCmd.DLTOBJ)
            .put(ParamCmd.OBJ, key.getQualifiedObject(ValCmd.CURLIB))
            .put(ParamCmd.OBJTYPE, ValCmd.fromString(key.getObjectType()));

          commandExecutor.executeCommand(dlt);
        } catch (Exception ignored) {} // This prevents breaking the loop
      }
    }

    assertFalse(errorFound, "Integration Test compilation failed");

  }

}
