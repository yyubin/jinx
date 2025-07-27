package org.jinx.cli;

import picocli.CommandLine;

@CommandLine.Command(
        name = "jinx",                                  // 명령어 이름
        mixinStandardHelpOptions = true,                // -h, --help 옵션 자동 추가
        version = "jinx 1.0",                           // -V, --version 옵션
        description = "JPA Entity 기반의 데이터베이스 마이그레이션 툴",
        subcommands = {
                DbCommand.class // 하위 명령어로 DbCommand를 등록합니다.
        }
)
public class JinxCli {
    public static void main(String[] args) {
        int exitCode = new CommandLine(new JinxCli()).execute(args);
        System.exit(exitCode);
    }
}
