package org.jinx.options;

/**
 * Jinx 전체에서 사용하는 설정 옵션 상수들을 정의합니다.
 * CLI와 Annotation Processor에서 동일한 키를 사용하여 설정 일관성을 보장합니다.
 */
public final class JinxOptions {

    private JinxOptions() {
    }

    /**
     * 프로파일 관련 설정
     */
    public static final class Profile {
        private Profile() {}

        /**
         * 기본 프로파일 이름
         */
        public static final String DEFAULT = "dev";

        /**
         * 프로파일 환경변수명
         */
        public static final String ENV_VAR = "JINX_PROFILE";

        /**
         * 설정 파일명
         */
        public static final String CONFIG_FILE = "jinx.yaml";

        /**
         * Annotation Processor에서 프로파일을 지정하는 옵션 키
         */
        public static final String PROCESSOR_KEY = "jinx.profile";
    }

    /**
     * 네이밍 관련 설정
     */
    public static final class Naming {
        private Naming() {}

        /**
         * 생성되는 제약조건/인덱스 이름의 최대 길이
         * 기본값: 30
         */
        public static final String MAX_LENGTH_KEY = "jinx.naming.maxLength";
        public static final int MAX_LENGTH_DEFAULT = 30;
    }

    // public static final class Database {
    //     public static final String URL_KEY = "jinx.database.url";
    //     public static final String USERNAME_KEY = "jinx.database.username";
    //     public static final String PASSWORD_KEY = "jinx.database.password";
    // }

    // public static final class Output {
    //     public static final String FORMAT_KEY = "jinx.output.format";
    //     public static final String FORMAT_DEFAULT = "sql";
    // }
}