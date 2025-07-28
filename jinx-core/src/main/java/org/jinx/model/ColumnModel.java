package org.jinx.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ColumnModel {
    private String columnName;
    private String javaType;
    private boolean isPrimaryKey;
    private boolean isNullable;
    private boolean isUnique;
    private int length;
    private int precision;
    private int scale;
    private String defaultValue;
    private GenerationStrategy generationStrategy;
    private String sequenceName;
    private String tableGeneratorName;
    private long identityStartValue;
    private int identityIncrement;
    private int identityCache;
    private long identityMinValue;
    private long identityMaxValue;
    private String[] identityOptions;
    private boolean isManualPrimaryKey;
    private boolean enumStringMapping;
    private String[] enumValues;
}
