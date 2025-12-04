package org.jinx.gradle;

import org.gradle.api.Action;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;

import javax.inject.Inject;

/**
 * Gradle DSL extension for Jinx configuration
 *
 * Example usage:
 * <pre>
 * jinx {
 *     profile = 'prod'
 *     naming {
 *         maxLength = 63
 *     }
 *     database {
 *         dialect = 'postgresql'
 *         url = 'jdbc:postgresql://localhost:5432/mydb'
 *     }
 *     output {
 *         format = 'liquibase'
 *         directory = 'src/main/resources/db/migration'
 *     }
 * }
 * </pre>
 */
public abstract class JinxExtension {

    private final NamingConfiguration naming;
    private final DatabaseConfiguration database;
    private final OutputConfiguration output;

    @Inject
    public JinxExtension(ObjectFactory objects) {
        this.naming = objects.newInstance(NamingConfiguration.class);
        this.database = objects.newInstance(DatabaseConfiguration.class);
        this.output = objects.newInstance(OutputConfiguration.class);
    }

    /**
     * 활성 프로파일 설정
     */
    public abstract Property<String> getProfile();

    /**
     * 네이밍 관련 설정
     */
    public NamingConfiguration getNaming() {
        return naming;
    }

    /**
     * 데이터베이스 관련 설정
     */
    public DatabaseConfiguration getDatabase() {
        return database;
    }

    /**
     * 출력 관련 설정
     */
    public OutputConfiguration getOutput() {
        return output;
    }

    /**
     * 네이밍 설정을 위한 DSL 블록
     */
    public void naming(Action<? super NamingConfiguration> action) {
        action.execute(naming);
    }

    /**
     * 데이터베이스 설정을 위한 DSL 블록
     */
    public void database(Action<? super DatabaseConfiguration> action) {
        action.execute(database);
    }

    /**
     * 출력 설정을 위한 DSL 블록
     */
    public void output(Action<? super OutputConfiguration> action) {
        action.execute(output);
    }

    /**
     * 네이밍 관련 설정
     */
    public static abstract class NamingConfiguration {
        /**
         * 생성되는 제약조건/인덱스 이름의 최대 길이
         */
        public abstract Property<Integer> getMaxLength();

        /**
         * 네이밍 전략 (SNAKE_CASE, NO_OP)
         * <p>
         * - SNAKE_CASE: 카멜케이스를 스네이크케이스로 변환 (maxLevel → max_level)
         * - NO_OP: 변환 없이 그대로 사용 (기본값)
         */
        public abstract Property<String> getStrategy();
    }

    /**
     * 데이터베이스 관련 설정
     */
    public static abstract class DatabaseConfiguration {
        /**
         * 데이터베이스 방언 (mysql, postgresql 등)
         */
        public abstract Property<String> getDialect();

        /**
         * 데이터베이스 URL
         */
        public abstract Property<String> getUrl();

        /**
         * 데이터베이스 사용자명
         */
        public abstract Property<String> getUsername();

        /**
         * 데이터베이스 비밀번호
         */
        public abstract Property<String> getPassword();
    }

    /**
     * 출력 관련 설정
     */
    public static abstract class OutputConfiguration {
        /**
         * 출력 형식 (sql, liquibase 등)
         */
        public abstract Property<String> getFormat();

        /**
         * 출력 디렉토리
         */
        public abstract Property<String> getDirectory();
    }
}