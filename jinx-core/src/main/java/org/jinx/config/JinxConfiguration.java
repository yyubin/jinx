package org.jinx.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.ToString;

import java.util.HashMap;
import java.util.Map;

@Data
public class JinxConfiguration {

    /**
     * 프로파일별 설정 맵
     */
    @JsonProperty("profiles")
    private Map<String, ProfileConfiguration> profiles = new HashMap<>();

    /**
     * 개별 프로파일 설정
     */
    @Data
    public static class ProfileConfiguration {

        @JsonProperty("naming")
        private NamingConfiguration naming;

        @JsonProperty("database")
        private DatabaseConfiguration database;

        @JsonProperty("output")
        private OutputConfiguration output;
    }

    /**
     * 네이밍 관련 설정
     */
    @Data
    public static class NamingConfiguration {

        @JsonProperty("maxLength")
        private Integer maxLength;
    }

    /**
     * 데이터베이스 관련 설정
     */
    @Data
    public static class DatabaseConfiguration {

        @JsonProperty("dialect")
        private String dialect;

        @JsonProperty("url")
        private String url;

        @JsonProperty("username")
        private String username;

        @ToString.Exclude
        private String password;
    }

    /**
     * 출력 관련 설정
     */
    @Data
    public static class OutputConfiguration {

        @JsonProperty("format")
        private String format;

        @JsonProperty("directory")
        private String directory;
    }
}
