package org.jinx.migration.liquibase.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;
import lombok.NonNull;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AddNotNullConstraintConfig {
    @NonNull
    private String tableName;          // 필수
    @NonNull private String columnName;         // 필수

    // 일부 DB/드라이버에서 필요할 수 있음
    private String columnDataType;     // 선택

    // 일부 DB는 NOT NULL도 제약명으로 관리(Oracle 등), Liquibase가 지원 -> 선택
    private String constraintName;     // 선택

    // NULL->NOT NULL 전환 시 기존 NULL 데이터 처리에 사용할 기본값(선택)
    private String defaultNullValue;   // 선택
}