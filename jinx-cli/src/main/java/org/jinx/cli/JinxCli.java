package org.jinx.cli;

import picocli.CommandLine;

/**
 * Main CLI entry point for Jinx database migration tool.
 * Provides database schema migration capabilities based on JPA entities.
 */
@CommandLine.Command(
        name = "jinx",
        mixinStandardHelpOptions = true,
        version = "jinx 1.0",
        description = "JPA Entity 기반의 데이터베이스 마이그레이션 툴",
        subcommands = {
                DbCommand.class
        }
)
public class JinxCli {
    public static void main(String[] args) {
        int exitCode = new CommandLine(new JinxCli()).execute(args);
        System.exit(exitCode);
    }
}
