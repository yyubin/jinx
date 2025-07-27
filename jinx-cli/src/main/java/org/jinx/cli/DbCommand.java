package org.jinx.cli;

import picocli.CommandLine;

@CommandLine.Command(
        name = "db",
        description = "데이터베이스 관련 명령어",
        subcommands = {
                MigrateCommand.class
        }
)
public class DbCommand {

}
