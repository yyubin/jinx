package org.jinx.cli;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MigrateCommandTest {

    private static final class Streams implements AutoCloseable {
        private final PrintStream origOut = System.out;
        private final PrintStream origErr = System.err;
        private final ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        private final ByteArrayOutputStream errBuf = new ByteArrayOutputStream();

        Streams() {
            System.setOut(new PrintStream(outBuf));
            System.setErr(new PrintStream(errBuf));
        }

        String out() {
            return outBuf.toString();
        }

        String err() {
            return errBuf.toString();
        }

        @Override
        public void close() {
            System.setOut(origOut);
            System.setErr(origErr);
        }
    }

    private static Path writeSchema(Path dir, LocalDateTime ts, String extraJson) throws IOException {
        String stamp = ts.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        Path p = dir.resolve("schema-" + stamp + ".json");
        Files.writeString(p, """
                {
                  "version":"%s",
                  "entities": %s
                }
                """.formatted(stamp, extraJson));
        return p;
    }


    @TempDir
    Path tmp;

    @Test
    @DisplayName("디렉터리 미존재 - exit 1 & 오류 메시지")
    void schemaDirNotFound() {
        Path bad = tmp.resolve("nope");
        try (Streams s = new Streams()) {
            int code = new CommandLine(new MigrateCommand())
                    .execute("-p", bad.toString());

            assertThat(code).isOne();
            assertThat(s.err()).contains("Schema directory not found");
        }
    }

    @Test
    @DisplayName("변경 없음 - \"No changes detected.\"")
    void noChangesDetected() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        writeSchema(tmp, now.minusSeconds(1), "{}");
        writeSchema(tmp, now, "{}");

        try (Streams s = new Streams()) {
            int code = new CommandLine(new MigrateCommand())
                    .execute("-p", tmp.toString());

            assertThat(code).isZero();
            assertThat(s.out()).contains("No changes detected.");
        }
    }

    @Test
    @DisplayName("미지원 dialect 지정 - exit 1 & Unsupported 메시지")
    void unsupportedDialect() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        writeSchema(tmp, now.minusSeconds(1), "{}");
        // 두 번째 스키마에 dummy entity 넣어 diff 발생
        writeSchema(tmp, now, "{ \"dummy\":{} }");

        try (Streams s = new Streams()) {
            int code = new CommandLine(new MigrateCommand())
                    .execute("-p", tmp.toString(), "--dialect", "oracle");

            assertThat(code).isOne();
            assertThat(s.err()).contains("Unsupported dialect");
        }
    }
}

