package org.jinx.spi.naming.impl;

import org.jinx.spi.naming.JinxNamingStrategy;

import java.util.List;

/**
 * 변환 없이 입력 그대로 반환하는 기본 네이밍 전략
 * <p>
 * 이 전략은 Jinx의 기본값으로, 기존 동작과의 하위 호환성을 유지합니다.
 * 명시적으로 네이밍 전략을 설정하지 않으면 이 전략이 사용됩니다.
 * <p>
 * 예시:
 * <ul>
 *   <li>필드명 "maxLevel" → 컬럼명 "maxLevel" (변환 없음)</li>
 *   <li>클래스명 "PersonaJpa" → 테이블명 "PersonaJpa" (변환 없음)</li>
 * </ul>
 */
public class NoOpNamingStrategy implements JinxNamingStrategy {

    @Override
    public String toPhysicalColumnName(String logicalName) {
        return logicalName;
    }

    @Override
    public String toPhysicalTableName(String logicalName) {
        return logicalName;
    }

    @Override
    public String toPhysicalConstraintName(String tableName, String constraintType, List<String> columnNames) {
        // 기존 Jinx의 제약조건 네이밍 로직 유지
        // 예: "uq_persona__max_level"
        String prefix = getConstraintPrefix(constraintType);
        String columns = String.join("_", columnNames);
        return prefix + "_" + tableName + "__" + columns;
    }

    @Override
    public String toPhysicalIndexName(String tableName, List<String> columnNames) {
        // 기존 Jinx의 인덱스 네이밍 로직 유지
        // 예: "ix_persona__max_level"
        String columns = String.join("_", columnNames);
        return "ix_" + tableName + "__" + columns;
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
