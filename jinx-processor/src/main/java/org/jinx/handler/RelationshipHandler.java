package org.jinx.handler;

import jakarta.persistence.*;
import org.jinx.context.ProcessingContext;
import org.jinx.descriptor.FieldAttributeDescriptor;
import org.jinx.descriptor.PropertyAttributeDescriptor;
import org.jinx.handler.relationship.*;
import org.jinx.model.*;
import org.jinx.util.AccessUtils;

import org.jinx.descriptor.AttributeDescriptor;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.util.*;
import java.util.Objects;
import java.util.stream.Collectors;

public class RelationshipHandler {
    private final ProcessingContext context;
    private final List<RelationshipProcessor> processors;
    
    public RelationshipHandler(ProcessingContext context) {
        this.context = context;
        
        // Initialize support classes
        RelationshipSupport relationshipSupport = new RelationshipSupport(context);
        RelationshipJoinSupport joinTableSupport = new RelationshipJoinSupport(context, relationshipSupport);

        
        // Initialize processors in order of precedence
        this.processors = Arrays.asList(
            new InverseRelationshipProcessor(context, relationshipSupport),
            new ToOneRelationshipProcessor(context),
            new OneToManyOwningFkProcessor(context, relationshipSupport),
            new OneToManyOwningJoinTableProcessor(context, relationshipSupport, joinTableSupport),
            new ManyToManyOwningProcessor(context, relationshipSupport, joinTableSupport)
        );

        processors.sort(Comparator.comparing(p -> p.order()));
    }

    /**
     * 엔티티 관계를 처리합니다.
     * @Access 어노테이션에 따라 FIELD 또는 PROPERTY 접근 방식을 선택하여
     * 필드/게터 중복 처리 문제를 해결합니다.
     */
    public void resolveRelationships(TypeElement ownerType, EntityModel ownerEntity) {
        // 1) 우선 디스크립터 캐시가 있다면 그대로 사용
        boolean resolvedFromCache = false;
        try {
            java.util.List<AttributeDescriptor> descriptors = context.getCachedDescriptors(ownerType);
            if (descriptors != null && !descriptors.isEmpty()) {
                for (AttributeDescriptor d : descriptors) {
                    resolve(d, ownerEntity);
                }
                resolvedFromCache = true;
            }
        } catch (Exception ignore) {
            // 캐시 미구현/예외 시 @Access 기반 스캔으로 폴백
        }

        // 2) 캐시에서 처리되지 않았다면 @Access 기반으로 스캔
        if (!resolvedFromCache) {
            AccessType accessType = AccessUtils.determineAccessType(ownerType);

            if (accessType == AccessType.FIELD) {
                // FIELD 접근: 필드의 관계 어노테이션만 스캔
                scanFieldsForRelationships(ownerType, ownerEntity);
            } else {
                // PROPERTY 접근: 게터 메서드의 관계 어노테이션만 스캔
                scanPropertiesForRelationships(ownerType, ownerEntity);
            }
        }
    }

    /**
     * 필드에서 관계 어노테이션 스캔
     */
    private void scanFieldsForRelationships(TypeElement ownerType, EntityModel ownerEntity) {
        for (Element e : ownerType.getEnclosedElements()) {
            if (e.getKind() == ElementKind.FIELD && e instanceof VariableElement ve) {
                if (hasRelationshipAnnotation(ve)) {
                    resolve(ve, ownerEntity);
                }
            }
        }
    }

    /**
     * 게터 메서드에서 관계 어노테이션 스캔
     */
    private void scanPropertiesForRelationships(TypeElement ownerType, EntityModel ownerEntity) {
        for (Element e : ownerType.getEnclosedElements()) {
            if (e.getKind() == ElementKind.METHOD && e instanceof ExecutableElement ex) {
                if (AccessUtils.isGetterMethod(ex) && hasRelationshipAnnotation(ex)) {
                    AttributeDescriptor pd = new PropertyAttributeDescriptor(
                            ex, context.getTypeUtils(), context.getElementUtils());
                    resolve(pd, ownerEntity);
                }
            }
        }
    }

    private boolean hasRelationshipAnnotation(VariableElement field) {
        return field.getAnnotation(ManyToOne.class) != null ||
               field.getAnnotation(OneToOne.class) != null ||
               field.getAnnotation(OneToMany.class) != null ||
               field.getAnnotation(ManyToMany.class) != null;
    }

    private boolean hasRelationshipAnnotation(ExecutableElement ex) {
        return ex.getAnnotation(ManyToOne.class) != null ||
                ex.getAnnotation(OneToOne.class)  != null ||
                ex.getAnnotation(OneToMany.class) != null ||
                ex.getAnnotation(ManyToMany.class)!= null;
    }

    private boolean hasRelationshipAnnotation(AttributeDescriptor descriptor) {
        return descriptor.getAnnotation(ManyToOne.class) != null ||
                descriptor.getAnnotation(OneToOne.class)  != null ||
                descriptor.getAnnotation(OneToMany.class) != null ||
                descriptor.getAnnotation(ManyToMany.class)!= null;
    }

    public void resolve(AttributeDescriptor descriptor, EntityModel entityModel) {
        boolean handled = false;
        for (RelationshipProcessor p : processors) {
            if (p.supports(descriptor)) { p.process(descriptor, entityModel); handled = true; break; }
        }
        if (!handled && hasRelationshipAnnotation(descriptor)) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "No registered processor can handle relation on "
                            + entityModel.getEntityName() + "." + descriptor.name(),
                    descriptor.elementForDiagnostics());
        }
    }

    /**
     * 호환성을 위한 VariableElement 오버로드
     * VariableElement를 AttributeDescriptor로 래핑해서 처리
     */
    public void resolve(VariableElement field, EntityModel ownerEntity) {
        AttributeDescriptor fieldAttr = new FieldAttributeDescriptor(field, context.getTypeUtils(), context.getElementUtils());
        resolve(fieldAttr, ownerEntity);
    }

    /**
     * @MapsId 지연 처리 패스 - 모든 관계/컬럼이 생성된 후 실행
     * PK 구조와 @MapsId.value()의 정합성을 검증하고 정확한 FK→PK 매핑을 생성
     */
    public void processMapsIdAttributes(TypeElement ownerType, EntityModel ownerEntity) {
        java.util.List<AttributeDescriptor> descriptors = context.getCachedDescriptors(ownerType);
        if (descriptors != null) {
            for (AttributeDescriptor d : descriptors) {
                if (d.hasAnnotation(MapsId.class)) {
                    processMapsIdAttribute(d, ownerEntity);
                }
            }
            return;
        }

        // 캐시가 없을 때만 Access로 스캔
        AccessType accessType = AccessUtils.determineAccessType(ownerType);

        if (accessType == AccessType.FIELD) {
            // 필드에서 @MapsId가 붙은 ToOne 관계 처리
            processMapsIdFromFields(ownerType, ownerEntity);
        } else {
            // 게터에서 @MapsId가 붙은 ToOne 관계 처리
            processMapsIdFromProperties(ownerType, ownerEntity);
        }
    }

    private void processMapsIdFromFields(TypeElement ownerType, EntityModel ownerEntity) {
        for (Element element : ownerType.getEnclosedElements()) {
            if (element.getKind() == ElementKind.FIELD && element instanceof VariableElement field) {
                if (field.getAnnotation(MapsId.class) != null) {
                    AttributeDescriptor descriptor = new FieldAttributeDescriptor(field, context.getTypeUtils(), context.getElementUtils());
                    processMapsIdAttribute(descriptor, ownerEntity);
                }
            }
        }
    }

    private void processMapsIdFromProperties(TypeElement ownerType, EntityModel ownerEntity) {
        for (Element element : ownerType.getEnclosedElements()) {
            if (element.getKind() == ElementKind.METHOD && element instanceof ExecutableElement method) {
                if (AccessUtils.isGetterMethod(method) && method.getAnnotation(MapsId.class) != null && hasRelationshipAnnotation(method)) {
                    AttributeDescriptor descriptor = new PropertyAttributeDescriptor(method, context.getTypeUtils(), context.getElementUtils());
                    processMapsIdAttribute(descriptor, ownerEntity);
                }
            }
        }
    }

    /**
     * 개별 @MapsId 속성 처리
     */
    public void processMapsIdAttribute(AttributeDescriptor descriptor, EntityModel ownerEntity) {
        MapsId mapsId = descriptor.getAnnotation(MapsId.class);
        String keyPath = (mapsId != null && !mapsId.value().isEmpty()) ? mapsId.value() : "";

        // 1) ToOne owning side만 대상
        ManyToOne m2o = descriptor.getAnnotation(ManyToOne.class);
        OneToOne o2o = descriptor.getAnnotation(OneToOne.class);
        if (m2o == null && (o2o == null || !o2o.mappedBy().isEmpty())) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                "@MapsId can only be used on owning side ToOne relationships", descriptor.elementForDiagnostics());
            ownerEntity.setValid(false);
            return;
        }

        // 2) 소유 엔티티 PK 수집
        List<ColumnModel> ownerPkCols = context.findAllPrimaryKeyColumns(ownerEntity);
        if (ownerPkCols.isEmpty()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                "@MapsId requires a primary key on " + ownerEntity.getEntityName(), descriptor.elementForDiagnostics());
            ownerEntity.setValid(false);
            return;
        }

        // 3) 해당 필드의 RelationshipModel 찾기
        RelationshipModel relationship = findToOneRelationshipFor(descriptor, ownerEntity);
        if (relationship == null) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                "Could not find relationship model for @MapsId field " + descriptor.name(), descriptor.elementForDiagnostics());
            ownerEntity.setValid(false);
            return;
        }

        if (!Objects.equals(relationship.getTableName(), ownerEntity.getTableName())) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                " @MapsId requires FK to be on owner's primary table. table=" + relationship.getTableName(),
                descriptor.elementForDiagnostics());
            ownerEntity.setValid(false);
            return;
        }

        List<String> fkColumns = relationship.getColumns();

        // 4) @MapsId.value() 처리
        if (keyPath.isEmpty()) {
            // 전체 PK 공유
            processFullPrimaryKeyMapping(descriptor, ownerEntity, relationship, fkColumns, ownerPkCols, keyPath);
        } else {
            // 특정 PK 속성 매핑
            processPartialPrimaryKeyMapping(descriptor, ownerEntity, relationship, fkColumns, ownerPkCols, keyPath);
        }
    }

    private void processFullPrimaryKeyMapping(AttributeDescriptor descriptor, EntityModel ownerEntity, 
                                             RelationshipModel relationship, List<String> fkColumns, 
                                             List<ColumnModel> ownerPkCols, String keyPath) {
        if (fkColumns.size() != ownerPkCols.size()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                "@MapsId without value must map all PK columns. expected=" + ownerPkCols.size()
                    + ", found=" + fkColumns.size(), descriptor.elementForDiagnostics());
            ownerEntity.setValid(false);
            return;
        }

        // PK 승격 확인 및 매핑 기록
        ensureAllArePrimaryKeys(ownerEntity, relationship.getTableName(), fkColumns, descriptor);
        
        List<String> ownerPkColumnNames = ownerPkCols.stream()
            .map(ColumnModel::getColumnName)
            .toList();
            
        recordMapsIdBindings(relationship, fkColumns, ownerPkColumnNames, keyPath);
    }

    private void processPartialPrimaryKeyMapping(AttributeDescriptor descriptor, EntityModel ownerEntity,
                                                RelationshipModel relationship, List<String> fkColumns,
                                                List<ColumnModel> ownerPkCols, String keyPath) {
        List<String> ownerPkAttrColumns = findPkColumnsForAttribute(ownerEntity, keyPath, descriptor);
        if (ownerPkAttrColumns.isEmpty()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                "@MapsId(\"" + keyPath + "\") could not find matching PK attribute on " + ownerEntity.getEntityName(),
                descriptor.elementForDiagnostics());
            ownerEntity.setValid(false);
            return;
        }

        if (fkColumns.size() != ownerPkAttrColumns.size()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                "@MapsId(\"" + keyPath + "\") column count mismatch. expected=" + ownerPkAttrColumns.size()
                    + ", found=" + fkColumns.size(), descriptor.elementForDiagnostics());
            ownerEntity.setValid(false);
            return;
        }

        ensureAllArePrimaryKeys(ownerEntity, relationship.getTableName(), fkColumns, descriptor);
        recordMapsIdBindings(relationship, fkColumns, ownerPkAttrColumns, keyPath);
    }

    /**
     * ToOne 관계에 대한 RelationshipModel 찾기
     */
    private RelationshipModel findToOneRelationshipFor(AttributeDescriptor d, EntityModel owner) {
        for (var rel : owner.getRelationships().values()) {
            if ((rel.getType() == RelationshipType.MANY_TO_ONE || rel.getType() == RelationshipType.ONE_TO_ONE)
                && Objects.equals(rel.getSourceAttributeName(), d.name())) {
                return rel;
            }
        }
        return null;
    }

    /**
     * PK 승격 확인 및 설정
     */
    private void ensureAllArePrimaryKeys(EntityModel ownerEntity, String tableName, List<String> columnNames, AttributeDescriptor descriptor) {
        for (String columnName : columnNames) {
            ColumnModel column = ownerEntity.findColumn(tableName, columnName);
            if (column == null) {
                context.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "Could not find column " + columnName + " for @MapsId PK promotion", descriptor.elementForDiagnostics());
                continue;
            }

            if (!column.isPrimaryKey()) {
                column.setPrimaryKey(true);
            }

            if (column.isNullable()) {
                column.setNullable(false);
            }
        }
        refreshPrimaryKeyConstraint(ownerEntity, tableName);
    }

    private void refreshPrimaryKeyConstraint(EntityModel entity, String tableName) {
        List<String> pkCols = entity.getColumns().values().stream()
            .filter(c -> tableName.equals(c.getTableName()) && c.isPrimaryKey())
            .map(ColumnModel::getColumnName)
            .sorted()
            .toList();

        if (pkCols.isEmpty()) return;

        // Find existing PK for the table
        ConstraintModel existing = entity.getConstraints().values().stream()
            .filter(c -> c.getType() == ConstraintType.PRIMARY_KEY && tableName.equals(c.getTableName()))
            .findFirst().orElse(null);

        String newPkName = context.getNaming().pkName(tableName, pkCols);

        // Update if existing PK is missing or columns differ
        if (existing == null || !new HashSet<>(existing.getColumns()).equals(new HashSet<>(pkCols))) {
            if (existing != null) {
                entity.getConstraints().remove(existing.getName());
            }
            ConstraintModel pk = ConstraintModel.builder()
                .name(newPkName).type(ConstraintType.PRIMARY_KEY)
                .tableName(tableName).columns(pkCols).build();
            entity.getConstraints().put(newPkName, pk);
        }
    }

    /**
     * @MapsId 매핑 기록
     */
    private void recordMapsIdBindings(RelationshipModel relationship, List<String> fkColumns, List<String> pkColumns, String keyPath) {
        relationship.setMapsIdKeyPath(keyPath);

        // FK컬럼 → PK컬럼 매핑 (순서대로 1:1 매핑)
        Map<String, String> bindings = relationship.getMapsIdBindings();
        if (bindings == null) {
            bindings = new HashMap<>();
            relationship.setMapsIdBindings(bindings);
        }
        for (int i = 0; i < Math.min(fkColumns.size(), pkColumns.size()); i++) {
            bindings.put(fkColumns.get(i), pkColumns.get(i));
        }
    }

    /**
     * 특정 PK 속성에 해당하는 컬럼 찾기 (IdClass/EmbeddedId 지원)
     */
    private List<String> findPkColumnsForAttribute(EntityModel ownerEntity, String attributeName, AttributeDescriptor where) {
        // 1. If user specifies an attribute path, but the PK is a single ID, it's an error.
        List<ColumnModel> allPkColumns = context.findAllPrimaryKeyColumns(ownerEntity);
        if (allPkColumns.size() == 1) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                "@MapsId(\"" + attributeName + "\") cannot specify an attribute name for an entity with a single-column primary key. " +
                "This is only supported for @EmbeddedId composite keys.", where.elementForDiagnostics());
            return List.of();
        }

        // 2. For composite keys, look up the attribute path in the context map.
        String fqcn = ownerEntity.getFqcn() != null ? ownerEntity.getFqcn() : ownerEntity.getEntityName();
        List<String> cols = context.getPkColumnsForAttribute(fqcn, attributeName);

        if (cols == null || cols.isEmpty()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                "@MapsId(\"" + attributeName + "\") could not be resolved to PK columns. " +
                "Ensure the attribute path is correct and the target entity uses @EmbeddedId.",
                where.elementForDiagnostics());
            return List.of();
        }
        return cols;
    }

}
