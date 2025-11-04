package org.jinx.cli;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for DbCommand.
 */
class DbCommandTest {

    private ByteArrayOutputStream outContent;
    private ByteArrayOutputStream errContent;
    private PrintStream originalOut;
    private PrintStream originalErr;

    @BeforeEach
    void setUp() {
        outContent = new ByteArrayOutputStream();
        errContent = new ByteArrayOutputStream();
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(outContent));
        System.setErr(new PrintStream(errContent));
    }

    void tearDown() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    @Test
    @DisplayName("Displays help for db command")
    void testDbCommand_Help() {
        try {
            ByteArrayOutputStream helpOut = new ByteArrayOutputStream();
            ByteArrayOutputStream helpErr = new ByteArrayOutputStream();
            PrintWriter pwOut = new PrintWriter(helpOut);
            PrintWriter pwErr = new PrintWriter(helpErr);
            int exitCode = new CommandLine(new JinxCli())
                    .setOut(pwOut)
                    .setErr(pwErr)
                    .execute("db", "--help");
            pwOut.flush();
            pwErr.flush();

            // Picocli may return exit code 0 or 2 when displaying help
            assertThat(exitCode).isIn(0, 2);
            String output = helpOut.toString() + helpErr.toString();
            assertThat(output).contains("데이터베이스");
        } finally {
            tearDown();
        }
    }

    @Test
    @DisplayName("Displays help or error when executed without subcommand")
    void testDbCommand_NoSubcommand() {
        try {
            int exitCode = new CommandLine(new JinxCli())
                    .execute("db");

            // Should display message about required subcommand or help
            // May have no output, so only verify exit code
            assertThat(exitCode).isNotZero();
        } finally {
            tearDown();
        }
    }

    @Test
    @DisplayName("Verifies db verify subcommand exists")
    void testDbCommand_VerifySubcommand() {
        try {
            ByteArrayOutputStream helpOut = new ByteArrayOutputStream();
            ByteArrayOutputStream helpErr = new ByteArrayOutputStream();
            PrintWriter pwOut = new PrintWriter(helpOut);
            PrintWriter pwErr = new PrintWriter(helpErr);
            int exitCode = new CommandLine(new JinxCli())
                    .setOut(pwOut)
                    .setErr(pwErr)
                    .execute("db", "verify", "--help");
            pwOut.flush();
            pwErr.flush();

            assertThat(exitCode).isZero();
            String output = helpOut.toString() + helpErr.toString();
            assertThat(output).contains("검증");
        } finally {
            tearDown();
        }
    }

    @Test
    @DisplayName("Verifies db migrate subcommand exists")
    void testDbCommand_MigrateSubcommand() {
        try {
            ByteArrayOutputStream helpOut = new ByteArrayOutputStream();
            ByteArrayOutputStream helpErr = new ByteArrayOutputStream();
            PrintWriter pwOut = new PrintWriter(helpOut);
            PrintWriter pwErr = new PrintWriter(helpErr);
            int exitCode = new CommandLine(new JinxCli())
                    .setOut(pwOut)
                    .setErr(pwErr)
                    .execute("db", "migrate", "--help");
            pwOut.flush();
            pwErr.flush();

            assertThat(exitCode).isZero();
            // Should display help for migrate command
            String output = helpOut.toString() + helpErr.toString();
            assertThat(output).isNotEmpty();
        } finally {
            tearDown();
        }
    }

    @Test
    @DisplayName("Verifies db promote-baseline subcommand exists")
    void testDbCommand_PromoteBaselineSubcommand() {
        try {
            ByteArrayOutputStream helpOut = new ByteArrayOutputStream();
            ByteArrayOutputStream helpErr = new ByteArrayOutputStream();
            PrintWriter pwOut = new PrintWriter(helpOut);
            PrintWriter pwErr = new PrintWriter(helpErr);
            int exitCode = new CommandLine(new JinxCli())
                    .setOut(pwOut)
                    .setErr(pwErr)
                    .execute("db", "promote-baseline", "--help");
            pwOut.flush();
            pwErr.flush();

            assertThat(exitCode).isZero();
            String output = helpOut.toString() + helpErr.toString();
            assertThat(output).contains("baseline");
        } finally {
            tearDown();
        }
    }

    @Test
    @DisplayName("Returns error for non-existent subcommand")
    void testDbCommand_InvalidSubcommand() {
        try {
            int exitCode = new CommandLine(new JinxCli())
                    .execute("db", "invalid-command");

            // Should return error for invalid command
            assertThat(exitCode).isNotZero();
        } finally {
            tearDown();
        }
    }
}
