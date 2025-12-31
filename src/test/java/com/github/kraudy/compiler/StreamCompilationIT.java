package com.github.kraudy.compiler;

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
  void testCompileFromStreamFile() throws Exception {
    TargetKey key = new TargetKey("curlib.HELLO.pgm.rpgle");

    /* Create source */
    String rpgleSource = 
      "**free\n" +
      "ctl-opt dftactgrp(*no);\n" +
      "\n" +
      "dsply 'Hello from stream file compilation test!';\n" +
      "*inlr = *on;\n" +
      "return;\n"
      ;

    String path = currentUser.getHomeDirectory() + "/" + System.currentTimeMillis() + key.getObjectName() + "." + key.getSourceType();

    this.ifsFile = new IFSFile(system, path);

    boolean created = this.ifsFile.createNewFile();

    IFSFileWriter writer = new IFSFileWriter(this.ifsFile, false);
    // Write the source string (handles conversion to file's CCSID)
    writer.write(rpgleSource);
    // Flush and close (close() also flushes)
    writer.close();

    /* Set stream file path */
    key.setStreamSourceFile(path);

    /* Create spec */
    String yamlContent = 
      "before:\n" +
        "  chglibl:\n" +
        "    LIBL: " + curlib + "\n" +
      "after:\n" +
        "  DltObj:\n" +
        "    OBJ: " + key.getObjectName() + "\n" +
        "    OBJTYPE: pgm\n" +
      "targets:\n" +
      "  " + key.asString() + ":\n" +
      "    params:\n" +
      "      TEXT: Hello World\n" +
      "      SRCSTMF: " + key.getStreamFile() + "\n"
      ;

    // Create temp YAML file
    Path tempYaml = Files.createTempFile("test", ".yaml");
    Files.write(tempYaml, yamlContent.getBytes());

    /* Now, compile! */
    try {
      BuildSpec spec = Utilities.deserializeYaml(tempYaml.toString());

      MasterCompiler compiler = new MasterCompiler(
        system,
        connection,
        spec,
        false,  // dryRun
        true,  // debug
        true,   // verbose - helpful for debugging test failures
        false,  // diff
        false   // noMigrate
      );

      // This should compile the program
      compiler.build();

      /* Check if no errors were found */
      assertFalse(compiler.foundCompilationError());

    } finally {
      /* Remove files */
      Files.deleteIfExists(tempYaml);
      
      if (ifsFile.exists()) {
        ifsFile.delete();
      }
    }
  }

}
