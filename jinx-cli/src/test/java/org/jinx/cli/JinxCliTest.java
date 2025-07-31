package org.jinx.cli;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.*;
import java.nio.file.*;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class JinxCliTest {

    @TempDir Path tmp;

    private Path writeSchema(LocalDateTime ts) throws IOException {
        String file = "schema-" + ts.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + ".json";
        Path p = tmp.resolve(file);
        Files.writeString(p, """
                {
                  "version": "%s",
                  "entities": {}
                }""".formatted(ts.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))));
        return p;
    }

    private static class StreamCaptor implements AutoCloseable {
        private final PrintStream origOut = System.out;
        private final PrintStream origErr = System.err;
        private final ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        private final ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        StreamCaptor() {
            System.setOut(new PrintStream(outBuf));
            System.setErr(new PrintStream(errBuf));
        }
        String out() { return outBuf.toString(); }
        String err() { return errBuf.toString(); }
        @Override public void close() {
            System.setOut(origOut);
            System.setErr(origErr);
        }
    }


    @Test
    @DisplayName("스키마 디렉터리 미존재 -> exit‑1 + 오류메시지")
    void dirNotFound() {
        Path notExist = tmp.resolve("no_such_dir");
        try (StreamCaptor sc = new StreamCaptor()) {
            int code = new CommandLine(new JinxCli())
                    .execute("db", "migrate", "-p", notExist.toString());

            assertThat(code).isEqualTo(1);
            assertThat(sc.err()).contains("Schema directory not found");
        }
    }

    @Test
    @DisplayName("두 스키마가 동일 -> No changes detected, exit‑0")
    void noChangesDetected() throws Exception {
        // 동일한 내용이지만 timestamp 가 다른 두 스키마
        writeSchema(LocalDateTime.of(2024,1,1,0,0,0));
        writeSchema(LocalDateTime.of(2024,1,2,0,0,0));

        try (StreamCaptor sc = new StreamCaptor()) {
            int code = new CommandLine(new JinxCli())
                    .execute("db", "migrate", "-p", tmp.toString());

            assertThat(code).isEqualTo(0);
            assertThat(sc.out()).contains("No changes detected.");
        }
    }

    @Test
    @DisplayName("지원하지 않는 dialect 지정 -> exit‑1 + IllegalArgumentException 메시지")
    void unsupportedDialect() throws Exception {

        writeSchema(LocalDateTime.of(2024,1,1,0,0,0));
        Files.writeString(writeSchema(LocalDateTime.of(2024,1,2,0,0,0)),
                """
                { "version":"20240102000000", "entities":{"dummy":{}} }""");

        try (StreamCaptor sc = new StreamCaptor()) {
            int code = new CommandLine(new JinxCli())
                    .execute("db", "migrate",
                            "-p", tmp.toString(),
                            "--dialect", "oracle");   // 아직 미지원이라고 가정

            assertThat(code).isEqualTo(1);
            assertThat(sc.err()).contains("Unsupported dialect");
        }
    }
}
