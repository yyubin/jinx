package org.jinx.migration.liquibase.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Insert 구문 본문. columns 는 기존 ColumnWrapper 재사용.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InsertDataConfig {
    private String catalogName;  // 선택
    private String schemaName;   // 선택
    private String tableName;    // 필수
    private List<ColumnWrapper> columns; // 필수: 각 column.value / valueNumeric / valueBoolean 등은 ColumnConfig에 매핑
}
