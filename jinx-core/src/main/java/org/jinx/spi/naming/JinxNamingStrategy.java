package org.jinx.spi.naming;

import java.util.List;

/**
 * Jinx의 네이밍 전략 인터페이스
 * <p>
 * 논리적 이름(Java 필드명, 클래스명)을 물리적 이름(DB 컬럼명, 테이블명)으로 변환합니다.
 * <p>
 * 구현체는 일관된 네이밍 컨벤션을 제공하며, 명시적 {@code @Column}, {@code @Table} 어노테이션보다
 * 낮은 우선순위를 가집니다.
 *
 * @see org.jinx.spi.naming.impl.NoOpNamingStrategy
 * @see org.jinx.spi.naming.impl.SnakeCaseNamingStrategy
 */
public interface JinxNamingStrategy {

    /**
     * 컬럼의 물리적 이름을 결정합니다.
     * <p>
     * 우선순위: 명시적 columnName > overrides > @Column.name() > <strong>namingStrategy</strong> > attribute.name()
     *
     * @param logicalName 논리적 이름 (Java 필드명, 예: "maxLevel")
     * @return 물리적 컬럼명 (예: "max_level")
     */
    String toPhysicalColumnName(String logicalName);

    /**
     * 테이블의 물리적 이름을 결정합니다.
     * <p>
     * 우선순위: @Table.name() > <strong>namingStrategy</strong> > 클래스명
     *
     * @param logicalName 논리적 이름 (엔티티 클래스명, 예: "PersonaJpa")
     * @return 물리적 테이블명 (예: "persona")
     */
    String toPhysicalTableName(String logicalName);

    /**
     * 제약조건의 물리적 이름을 결정합니다.
     *
     * @param tableName 테이블명
     * @param constraintType 제약조건 타입 (UNIQUE, FK, PK, CHECK 등)
     * @param columnNames 관련 컬럼명 목록
     * @return 물리적 제약조건명 (예: "uq_persona__max_level")
     */
    String toPhysicalConstraintName(String tableName, String constraintType, List<String> columnNames);

    /**
     * 인덱스의 물리적 이름을 결정합니다.
     *
     * @param tableName 테이블명
     * @param columnNames 인덱스 컬럼명 목록
     * @return 물리적 인덱스명 (예: "ix_persona__max_level")
     */
    String toPhysicalIndexName(String tableName, List<String> columnNames);
}
