package org.jinx.migration.liquibase.model;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;
import lombok.NonNull;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DropNotNullConstraintConfig {
    @NonNull
    private String tableName;        // 필수
    @NonNull private String columnName;       // 필수

    // 일부 DB는 드롭 시에도 타입 명시 요구
    private String columnDataType;   // 선택
}