package org.jinx.model;

import jakarta.persistence.EnumType;
import jakarta.persistence.FetchType;
import jakarta.persistence.TemporalType;
import lombok.Builder;
import lombok.Data;

import java.util.Objects;

@Data
@Builder
public class ColumnModel {
    @Builder.Default private String tableName = "";
    private String columnName;
    private String javaType;
    @Builder.Default private String comment = null; // Added for @Column(comment)
    @Builder.Default private boolean isPrimaryKey = false;
    @Builder.Default private boolean isNullable = true;
    @Builder.Default private boolean isUnique = false;
    @Builder.Default private int length = 255;
    @Builder.Default private int precision = 0;
    @Builder.Default private int scale = 0;
    @Builder.Default private String defaultValue = null;
    @Builder.Default private GenerationStrategy generationStrategy = GenerationStrategy.NONE;
    @Builder.Default private String sequenceName = null;
    @Builder.Default private String tableGeneratorName = null;
    @Builder.Default private long identityStartValue = 1;
    @Builder.Default private int identityIncrement = 1;
    @Builder.Default private int identityCache = 0;
    @Builder.Default private long identityMinValue = Long.MIN_VALUE;
    @Builder.Default private long identityMaxValue = Long.MAX_VALUE;
    @Builder.Default private String[] identityOptions = new String[]{};
    @Builder.Default private boolean isManualPrimaryKey = false;
    @Builder.Default private boolean enumStringMapping = false;
    @Builder.Default private String[] enumValues = new String[]{};
    @Builder.Default private boolean isLob = false;
    @Builder.Default private JdbcType jdbcType = null;
    @Builder.Default private FetchType fetchType = FetchType.EAGER;
    @Builder.Default private boolean isOptional = true;
    @Builder.Default private boolean isVersion = false;
    @Builder.Default private String conversionClass = null;
    @Builder.Default private TemporalType temporalType = null;
    @Builder.Default private EnumType enumerationType = null; // For @Enumerated
    @Builder.Default private boolean isMapKey = false; // Added for @MapKey*
    @Builder.Default private String mapKeyType = null; // e.g., "entity:fieldName" or null
    @Builder.Default private String[] mapKeyEnumValues = new String[]{}; // For @MapKeyEnumerated
    @Builder.Default private TemporalType mapKeyTemporalType = null; // For @MapKeyTemporal

    public long getAttributeHash() {
        return Objects.hash(columnName, javaType, length, precision, scale, isNullable, isUnique, defaultValue,
                temporalType, enumerationType);
    }
}