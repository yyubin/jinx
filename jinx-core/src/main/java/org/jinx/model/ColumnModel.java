package org.jinx.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ColumnModel {
    private String columnName;
    private String javaType;
    private String sqlType;
    private boolean isPrimaryKey;
    private boolean isNullable;
    private boolean isUnique;
    private int length;

    private int precision; // DECIMAL 타입용
    private int scale;    // DECIMAL 타입용
    private String defaultValue; // 기본값
}
