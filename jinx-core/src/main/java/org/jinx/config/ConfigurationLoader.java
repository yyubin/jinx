package org.jinx.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.jinx.config.model.JinxConfiguration;
import org.jinx.options.JinxOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;

public class ConfigurationLoader {

    private static final String CONFIG_FILE_NAME = JinxOptions.Profile.CONFIG_FILE;
    private static final String DEFAULT_PROFILE = JinxOptions.Profile.DEFAULT;
    private static final String PROFILE_ENV_VAR = JinxOptions.Profile.ENV_VAR;

    private final ObjectMapper yamlMapper;
    private final Path startDirectory;

    public ConfigurationLoader() {
        this(Paths.get("").toAbsolutePath());
    }

    public ConfigurationLoader(Path startDirectory) {
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
        this.startDirectory = startDirectory;
    }

    /**
     * 설정을 로드하고 지정된 프로파일을 적용
     *
     * 우선순위: CLI 프로파일 > 환경변수 > 기본값(dev)
     *
     * @param cliProfile CLI에서 지정된 프로파일 (null 가능)
     * @return 해석된 설정 맵
     */
    public Map<String, String> loadConfiguration(String cliProfile) {
        String activeProfile = resolveActiveProfile(cliProfile);

        Optional<JinxConfiguration> config = findAndLoadConfiguration();
        if (config.isEmpty()) {
            // 설정 파일이 없으면 기본값 사용
            return createDefaultConfiguration();
        }

        return extractConfigurationForProfile(config.get(), activeProfile);
    }

    /**
     * 활성 프로파일을 결정합니다.
     */
    private String resolveActiveProfile(String cliProfile) {
        if (cliProfile != null && !cliProfile.trim().isEmpty()) {
            return cliProfile;
        }

        String envProfile = System.getenv(PROFILE_ENV_VAR);
        if (envProfile != null && !envProfile.trim().isEmpty()) {
            return envProfile;
        }

        return DEFAULT_PROFILE;
    }

    /**
     * 설정 파일을 찾아서 로드합니다.
     * 시작 디렉토리부터 상위 디렉토리로 올라가며 jinx.yaml을 찾습니다.
     */
    private Optional<JinxConfiguration> findAndLoadConfiguration() {
        Path currentDir = startDirectory;

        while (currentDir != null) {
            Path configFile = currentDir.resolve(CONFIG_FILE_NAME);
            if (Files.exists(configFile)) {
                try {
                    JinxConfiguration config = yamlMapper.readValue(configFile.toFile(), JinxConfiguration.class);
                    return Optional.of(config);
                } catch (IOException e) {
                    System.err.println("Warning: Failed to parse " + configFile + ": " + e.getMessage());
                    return Optional.empty();
                }
            }
            currentDir = currentDir.getParent();
        }

        return Optional.empty();
    }

    /**
     * 특정 프로파일의 설정을 추출하여 키-값 맵으로 변환합니다.
     */
    private Map<String, String> extractConfigurationForProfile(JinxConfiguration config, String profile) {
        var profileConfig = config.getProfiles().get(profile);
        if (profileConfig == null) {
            System.err.println("Warning: Profile '" + profile + "' not found in configuration. Using defaults.");
            return createDefaultConfiguration();
        }

        var configMap = new HashMap<>(createDefaultConfiguration());

        // naming 설정 적용
        if (profileConfig.getNaming() != null && profileConfig.getNaming().getMaxLength() != null) {
            configMap.put(JinxOptions.Naming.MAX_LENGTH_KEY,
                         String.valueOf(profileConfig.getNaming().getMaxLength()));
        }

        // 향후 database, output 설정들도 여기에 추가

        return configMap;
    }

    /**
     * 기본 설정 맵을 생성합니다.
     */
    private Map<String, String> createDefaultConfiguration() {
        return Map.of(
            JinxOptions.Naming.MAX_LENGTH_KEY, String.valueOf(JinxOptions.Naming.MAX_LENGTH_DEFAULT)
        );
    }
}