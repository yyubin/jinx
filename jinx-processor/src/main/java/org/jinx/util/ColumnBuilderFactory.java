package org.jinx.util;

import jakarta.persistence.*;
import org.jinx.context.ProcessingContext;
import org.jinx.descriptor.AttributeDescriptor;
import org.jinx.model.ColumnModel;
import org.jinx.model.GenerationStrategy;

import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import java.util.List;
import java.util.Map;

public class ColumnBuilderFactory {

    public static ColumnModel.ColumnModelBuilder from(VariableElement field, TypeMirror typeHint, String columnName,
                                                      ProcessingContext context, Map<String, String> overrides) {
        Column column = field.getAnnotation(Column.class);
        String finalColumnName = overrides.getOrDefault(
                field.getSimpleName().toString(),
                column != null && !column.name().isEmpty() ? column.name() : columnName
        );

        return ColumnModel.builder()
                .columnName(finalColumnName)
                .javaType(typeHint != null ? typeHint.toString() : field.asType().toString())
                .isPrimaryKey(field.getAnnotation(Id.class) != null || field.getAnnotation(EmbeddedId.class) != null)
                .isNullable(column == null || column.nullable())
                .length(column != null ? column.length() : 255)
                .precision(column != null ? column.precision() : 0)
                .scale(column != null ? column.scale() : 0)
                .sqlTypeOverride(column != null && !column.columnDefinition().isEmpty() ? column.columnDefinition() : null)
                .generationStrategy(GenerationStrategy.NONE);
    }

    public static ColumnModel.ColumnModelBuilder fromAttributeDescriptor(AttributeDescriptor attribute, TypeMirror type, String columnName,
                                                                          ProcessingContext context, Map<String, String> overrides) {
        Column column = attribute.getAnnotation(Column.class);

        // Use same priority-based column name resolution as AttributeBasedEntityResolver
        String finalColumnName = determineColumnName(attribute, columnName, column, overrides, context);

        boolean isPk = attribute.hasAnnotation(Id.class) || attribute.hasAnnotation(EmbeddedId.class);
        TypeMirror effectiveType = (type != null) ? type : attribute.type();
        GenerationStrategy genStrategy = GenerationStrategy.NONE;
        GeneratedValue gv = attribute.getAnnotation(GeneratedValue.class);
        if (gv != null) {
            switch (gv.strategy()) {
                case IDENTITY -> genStrategy = GenerationStrategy.IDENTITY;
                case AUTO -> genStrategy = GenerationStrategy.AUTO;
                case SEQUENCE -> genStrategy = GenerationStrategy.SEQUENCE;
                case TABLE -> genStrategy = GenerationStrategy.TABLE;
                default -> genStrategy = GenerationStrategy.NONE;
            }
        }

        ColumnModel.ColumnModelBuilder builder = ColumnModel.builder()
                .columnName(finalColumnName)
                .javaType(effectiveType.toString())
                .isPrimaryKey(isPk)
                .isNullable((column == null || column.nullable()) && !isPk)
                .length(column != null ? column.length() : 255)
                .precision(column != null ? column.precision() : 0)
                .scale(column != null ? column.scale() : 0)
                .sqlTypeOverride(column != null && !column.columnDefinition().isEmpty() ? column.columnDefinition() : null)
                .generationStrategy(genStrategy);
        
        // Apply table name override if provided
        String tableNameOverride = overrides.get("tableName");
        if (isNotBlank(tableNameOverride)) {
            builder.tableName(tableNameOverride);
        } else if (column != null && isNotBlank(column.table())) {
            builder.tableName(column.table());
        }
        
        return builder;
    }

    /**
     * Determine column name using priority-based resolution
     * Priority: explicit columnName > overrides > @Column.name() > namingStrategy > attribute.name()
     */
    private static String determineColumnName(AttributeDescriptor attribute, String columnName, Column column, Map<String, String> overrides, ProcessingContext context) {
        // Priority 1: Explicit parameter columnName
        if (isNotBlank(columnName)) {
            return columnName;
        }

        // Priority 2: Overrides map for this attribute
        String attributeName = attribute.name();
        String overrideName = overrides.get(attributeName);
        if (isNotBlank(overrideName)) {
            return overrideName;
        }

        // Priority 3: @Column.name() annotation
        if (column != null && isNotBlank(column.name())) {
            return column.name();
        }

        // Priority 4: NamingStrategy transformation
        if (context != null && context.getNamingStrategy() != null) {
            return context.getNamingStrategy().toPhysicalColumnName(attributeName);
        }

        // Priority 5: Attribute name (fallback)
        return attributeName;
    }

    public static ColumnModel fromType(TypeMirror type, String columnName, String tableName) {
        return ColumnModel.builder()
                .columnName(columnName)
                .tableName(tableName)
                .javaType(type.toString())
                .isNullable(true)
                .build();
    }
    
    private static boolean isNotBlank(String s) {
        return s != null && !s.isEmpty();
    }

    /**
     * {@code AttributeConverter<X, Y>}의 Y 타입(DB 저장 타입)을 추출한다.
     * <p>
     * 어노테이션 프로세서 환경에서 제네릭 인터페이스를 탐색하여 Y 타입의 FQCN을 반환한다.
     * 추출한 값은 {@link org.jinx.model.ColumnModel#getConverterOutputType()}에 저장되며,
     * DDL/Liquibase 타입 결정 시 컨버터 클래스명 대신 실제 DB 저장 타입으로 매핑에 사용된다.
     *
     * @param converterMirror {@code @Convert(converter=...)}로 지정된 컨버터 클래스의 {@link TypeMirror}
     * @return Y 타입의 FQCN (예: {@code "java.lang.String"}), 추출 불가 시 {@code null}
     */
    public static String extractConverterOutputType(TypeMirror converterMirror) {
        if (!(converterMirror instanceof DeclaredType converterDeclaredType)) {
            return null;
        }
        TypeElement converterElement = (TypeElement) converterDeclaredType.asElement();
        for (TypeMirror iface : converterElement.getInterfaces()) {
            // jakarta.persistence.AttributeConverter<X, Y> 인터페이스를 탐색한다
            if (iface.toString().startsWith("jakarta.persistence.AttributeConverter")) {
                List<? extends TypeMirror> typeArgs = ((DeclaredType) iface).getTypeArguments();
                if (typeArgs.size() == 2) {
                    // 두 번째 타입 파라미터 Y (DB 저장 타입)를 반환한다
                    return typeArgs.get(1).toString();
                }
            }
        }
        return null;
    }

}
