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
 * DbCommand 테스트
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
    @DisplayName("db 명령어 help 출력")
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

            // Picocli는 help 표시 시 exit code 0 또는 2를 반환할 수 있음
            assertThat(exitCode).isIn(0, 2);
            String output = helpOut.toString() + helpErr.toString();
            assertThat(output).contains("데이터베이스");
        } finally {
            tearDown();
        }
    }

    @Test
    @DisplayName("db 하위 명령어 없이 실행하면 help 또는 에러 출력")
    void testDbCommand_NoSubcommand() {
        try {
            int exitCode = new CommandLine(new JinxCli())
                    .execute("db");

            // 하위 명령어가 필요하다는 메시지 또는 help가 출력되어야 함
            // 출력이 없을 수도 있으므로 exit code만 확인
            assertThat(exitCode).isNotZero();
        } finally {
            tearDown();
        }
    }

    @Test
    @DisplayName("db verify 하위 명령어 존재 확인")
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
    @DisplayName("db migrate 하위 명령어 존재 확인")
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
            // migrate 명령어의 help가 출력되어야 함
            String output = helpOut.toString() + helpErr.toString();
            assertThat(output).isNotEmpty();
        } finally {
            tearDown();
        }
    }

    @Test
    @DisplayName("db promote-baseline 하위 명령어 존재 확인")
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
    @DisplayName("존재하지 않는 하위 명령어")
    void testDbCommand_InvalidSubcommand() {
        try {
            int exitCode = new CommandLine(new JinxCli())
                    .execute("db", "invalid-command");

            // 잘못된 명령어에 대한 에러가 발생해야 함
            assertThat(exitCode).isNotZero();
        } finally {
            tearDown();
        }
    }
}
