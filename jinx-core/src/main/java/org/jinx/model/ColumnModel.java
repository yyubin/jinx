package org.jinx.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.EnumType;
import jakarta.persistence.FetchType;
import jakarta.persistence.TemporalType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Objects;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ColumnModel {
    @Builder.Default private String tableName = "";
    private String columnName;
    private String javaType;
    @Builder.Default private String comment = null; // Added for @Column(comment)
    @Builder.Default
    @JsonProperty("primaryKey")
    private boolean isPrimaryKey = false;
    @Builder.Default
    @JsonProperty("nullable")
    private boolean isNullable = true;
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
    @Builder.Default
    @JsonProperty("manualPrimaryKey")
    private boolean isManualPrimaryKey = false;
    @Builder.Default private boolean enumStringMapping = false;
    @Builder.Default private String[] enumValues = new String[]{};
    @Builder.Default
    @JsonProperty("lob")
    private boolean isLob = false;
    @Builder.Default private JdbcType jdbcType = null;
    @Builder.Default private FetchType fetchType = FetchType.EAGER;
    @Builder.Default
    @JsonProperty("optional")
    private boolean isOptional = true;
    @Builder.Default
    @JsonProperty("version")
    private boolean isVersion = false;
    @Builder.Default private String conversionClass = null;
    @Builder.Default private TemporalType temporalType = null;
    @Builder.Default private EnumType enumerationType = null; // For @Enumerated
    @Builder.Default
    @JsonProperty("mapKey")
    private boolean isMapKey = false; // Added for @MapKey*
    @Builder.Default private String mapKeyType = null; // e.g., "entity:fieldName" or null
    @Builder.Default private String[] mapKeyEnumValues = new String[]{}; // For @MapKeyEnumerated
    @Builder.Default private TemporalType mapKeyTemporalType = null; // For @MapKeyTemporal
    @Builder.Default private ColumnKind columnKind = ColumnKind.NORMAL;
    @Builder.Default private String sqlTypeOverride = null; // For @Column(columnDefinition)

    // Discriminator metadata
    private jakarta.persistence.DiscriminatorType discriminatorType; // optional
    private String columnDefinition; // optional
    private String options; // optional (JPA 3.2)

    public enum ColumnKind { NORMAL, DISCRIMINATOR }

    @JsonIgnore
    public long getAttributeHash() {
        return Objects.hash(columnName, javaType, length, precision, scale, isNullable, defaultValue,
                temporalType, enumerationType, enumStringMapping,
                java.util.Arrays.hashCode(enumValues), mapKeyTemporalType, java.util.Arrays.hashCode(mapKeyEnumValues),
                columnKind, discriminatorType, columnDefinition, options, sqlTypeOverride);
    }

    @JsonIgnore
    public long getAttributeHashExceptName() {
        return Objects.hash(tableName, javaType, length, precision, scale, isNullable, defaultValue,
                temporalType, enumerationType, enumStringMapping, isPrimaryKey, isLob, jdbcType,
                fetchType, isOptional, isVersion, conversionClass, generationStrategy, sequenceName,
                tableGeneratorName, identityStartValue, identityIncrement, identityCache,
                identityMinValue, identityMaxValue, java.util.Arrays.hashCode(identityOptions),
                isManualPrimaryKey, java.util.Arrays.hashCode(enumValues), mapKeyTemporalType,
                java.util.Arrays.hashCode(mapKeyEnumValues), columnKind, discriminatorType,
                columnDefinition, options, sqlTypeOverride, isMapKey, mapKeyType);
    }
}