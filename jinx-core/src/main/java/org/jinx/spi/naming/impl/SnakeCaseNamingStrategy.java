package org.jinx.spi.naming.impl;

import org.jinx.spi.naming.JinxNamingStrategy;

import java.util.List;

/**
 * 카멜케이스를 스네이크케이스로 변환하는 네이밍 전략
 * <p>
 * 이 전략은 Java의 카멜케이스 네이밍 컨벤션을 데이터베이스의 스네이크케이스 컨벤션으로 자동 변환합니다.
 * <p>
 * 변환 예시:
 * <ul>
 *   <li>필드명 "maxLevel" → 컬럼명 "max_level"</li>
 *   <li>필드명 "userName" → 컬럼명 "user_name"</li>
 *   <li>필드명 "HTTPServer" → 컬럼명 "http_server"</li>
 *   <li>클래스명 "PersonaJpa" → 테이블명 "persona"</li>
 * </ul>
 * <p>
 * 테이블명 변환 시 "Jpa", "Entity" 등의 접미사를 자동으로 제거합니다.
 */
public class SnakeCaseNamingStrategy implements JinxNamingStrategy {

    @Override
    public String toPhysicalColumnName(String logicalName) {
        return toSnakeCase(logicalName);
    }

    @Override
    public String toPhysicalTableName(String logicalName) {
        // "PersonaJpa" → "Persona" → "persona"
        String withoutSuffix = removeSuffix(logicalName, "Jpa", "Entity");
        return toSnakeCase(withoutSuffix);
    }

    @Override
    public String toPhysicalConstraintName(String tableName, String constraintType, List<String> columnNames) {
        String prefix = getConstraintPrefix(constraintType);
        String columns = String.join("_", columnNames);
        return prefix + "_" + tableName + "__" + columns;
    }

    @Override
    public String toPhysicalIndexName(String tableName, List<String> columnNames) {
        String columns = String.join("_", columnNames);
        return "ix_" + tableName + "__" + columns;
    }

    /**
     * 카멜케이스를 스네이크케이스로 변환
     * <p>
     * 변환 규칙:
     * <ul>
     *   <li>"maxLevel" → "max_level"</li>
     *   <li>"HTTPServer" → "http_server"</li>
     *   <li>"getHTTPResponseCode" → "get_http_response_code"</li>
     *   <li>"get2HTTPResponse" → "get2_http_response"</li>
     * </ul>
     * <p>
     * 알고리즘:
     * <ol>
     *   <li>대문자 발견 시 앞에 언더스코어 삽입 (첫 문자 제외)</li>
     *   <li>연속된 대문자 처리: 마지막 대문자 전에 언더스코어 삽입</li>
     *   <li>모든 문자를 소문자로 변환</li>
     * </ol>
     *
     * @param camelCase 카멜케이스 문자열
     * @return 스네이크케이스 문자열
     */
    private String toSnakeCase(String camelCase) {
        if (camelCase == null || camelCase.isEmpty()) {
            return camelCase;
        }

        StringBuilder result = new StringBuilder();
        char[] chars = camelCase.toCharArray();

        for (int i = 0; i < chars.length; i++) {
            char current = chars[i];

            if (Character.isUpperCase(current)) {
                // 첫 문자가 아니고, 언더스코어 삽입 조건 확인
                if (i > 0 && shouldInsertUnderscore(chars, i)) {
                    result.append('_');
                }
                result.append(Character.toLowerCase(current));
            } else {
                result.append(current);
            }
        }

        return result.toString();
    }

    /**
     * 언더스코어를 삽입해야 하는지 판단
     * <p>
     * 다음 경우에 언더스코어 삽입:
     * <ul>
     *   <li>이전 문자가 소문자인 경우 (예: "maxLevel" → "max_Level")</li>
     *   <li>이전 문자가 숫자인 경우 (예: "get2HTTP" → "get2_HTTP")</li>
     *   <li>다음 문자가 소문자인 경우 - 연속된 대문자의 마지막 (예: "HTTPServer" → "HTTP_Server")</li>
     * </ul>
     *
     * @param chars 문자 배열
     * @param index 현재 인덱스 (대문자 위치)
     * @return 언더스코어 삽입 여부
     */
    private boolean shouldInsertUnderscore(char[] chars, int index) {
        char prev = chars[index - 1];
        char current = chars[index];

        // 이전 문자가 소문자이거나 숫자인 경우
        if (Character.isLowerCase(prev) || Character.isDigit(prev)) {
            return true;
        }

        // 현재와 이전 모두 대문자이고, 다음 문자가 소문자인 경우
        // 예: "HTTPServer" → "HTTP" + "_" + "Server"
        if (Character.isUpperCase(prev) && index < chars.length - 1) {
            char next = chars[index + 1];
            if (Character.isLowerCase(next)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 문자열에서 지정된 접미사 제거
     *
     * @param name 원본 문자열
     * @param suffixes 제거할 접미사 목록
     * @return 접미사가 제거된 문자열
     */
    private String removeSuffix(String name, String... suffixes) {
        for (String suffix : suffixes) {
            if (name.endsWith(suffix)) {
                return name.substring(0, name.length() - suffix.length());
            }
        }
        return name;
    }

    /**
     * 제약조건 타입에 따른 prefix 반환
     */
    private String getConstraintPrefix(String constraintType) {
        if (constraintType == null) {
            return "ct";
        }
        return switch (constraintType.toUpperCase()) {
            case "UNIQUE" -> "uq";
            case "FOREIGN_KEY", "FK" -> "fk";
            case "PRIMARY_KEY", "PK" -> "pk";
            case "CHECK" -> "ck";
            default -> "ct";
        };
    }
}
