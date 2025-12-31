package com.github.kraudy.compiler;

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
