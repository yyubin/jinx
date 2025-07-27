package org.jinx.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.auto.service.AutoService;
import jakarta.persistence.*;
import org.jinx.annotation.*;
import org.jinx.migration.internal.MySQLDialect;
import org.jinx.model.*;
import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@AutoService(Processor.class)
@SupportedAnnotationTypes({
        "jakarta.persistence.Entity",
        "jakarta.persistence.Table",
        "jakarta.persistence.Column",
        "jakarta.persistence.Id",
        "jakarta.persistence.Index",
        "jakarta.persistence.ManyToOne",
        "jakarta.persistence.JoinColumn",
        "jakarta.persistence.JoinTable",
        "jakarta.persistence.ManyToMany",
        "jakarta.persistence.OneToOne",
        "jakarta.persistence.MapsId",
        "jakarta.persistence.Embedded",
        "jakarta.persistence.Embeddable",
        "jakarta.persistence.Inheritance",
        "jakarta.persistence.DiscriminatorColumn",
        "org.jinx.annotation.Constraint",
        "org.jinx.annotation.Constraints"
})
@SupportedSourceVersion(SourceVersion.RELEASE_17)
public class JpaSqlGeneratorProcessor extends AbstractProcessor {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private SchemaModel schemaModel;

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (!roundEnv.processingOver()) {
            return true;           // 중간 라운드에서는 패스
        }

        schemaModel = SchemaModel.builder()
                .version(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")))
                .build();

        // Pass 1: 기본 정보 수집
        for (Element element : roundEnv.getElementsAnnotatedWith(Entity.class)) {
            if (element.getKind() == ElementKind.CLASS) {
                buildBasicEntityModel((TypeElement) element);
            }
        }

        // Pass 2: 관계 및 상속 처리
        for (Element element : roundEnv.getElementsAnnotatedWith(Entity.class)) {
            if (element.getKind() == ElementKind.CLASS) {
                resolveComplexStructures((TypeElement) element, roundEnv);
            }
        }

        // JSON 저장
        saveModelToJson();
        return true;
    }

    private void buildBasicEntityModel(TypeElement typeElement) {
        Table table = typeElement.getAnnotation(Table.class);
        String tableName = table != null && !table.name().isEmpty() ? table.name() : typeElement.getSimpleName().toString();
        String entityName = typeElement.getQualifiedName().toString();

        if (schemaModel.getEntities().containsKey(entityName)) {
            processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                    "Duplicate entity name: " + entityName);
            return;
        }

        EntityModel entity = EntityModel.builder()
                .entityName(entityName)
                .tableName(tableName)
                .build();

        // 클래스 레벨 제약조건 처리
        processConstraints(typeElement, null, entity.getConstraints());

        // 필드 처리 (@Column, @Id, @Embedded)
        for (Element enclosed : typeElement.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.FIELD) continue;
            VariableElement field = (VariableElement) enclosed;
            Column column = field.getAnnotation(Column.class);
            Embedded embedded = field.getAnnotation(Embedded.class);

            processConstraints(field, field.getSimpleName().toString(), entity.getConstraints());

            if (embedded != null) {
                processEmbedded(field, entity.getColumns(), entity.getConstraints(), new HashSet<>());
                continue;
            }

            if (column != null) {
                ColumnModel columnModel = ColumnModel.builder()
                        .columnName(column.name().isEmpty() ? field.getSimpleName().toString() : column.name())
                        .javaType(field.asType().toString())
                        .sqlType(MySQLDialect.mapJavaTypeToSqlType(field.asType().toString(), column))
                        .isPrimaryKey(field.getAnnotation(Id.class) != null)
                        .isNullable(column.nullable())
                        .isUnique(column.unique())
                        .length(column.length())
                        .precision(column.precision())
                        .scale(column.scale())
                        .defaultValue(column.columnDefinition().isEmpty() ? null : column.columnDefinition())
                        .build();
                if (entity.getColumns().containsKey(columnModel.getColumnName())) {
                    processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                            "Duplicate column name: " + columnModel.getColumnName() + " in entity " + entityName);
                    return;
                }
                entity.getColumns().put(columnModel.getColumnName(), columnModel);
            }
        }

        // 인덱스 처리
        if (table != null) {
            for (Index index : table.indexes()) {
                IndexModel indexModel = IndexModel.builder()
                        .indexName(index.name())
                        .columnNames(Arrays.asList(index.columnList().split(",\\s*")))
                        .isUnique(index.unique())
                        .build();
                if (entity.getIndexes().containsKey(indexModel.getIndexName())) {
                    processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                            "Duplicate index name: " + indexModel.getIndexName() + " in entity " + entityName);
                    return;
                }
                entity.getIndexes().put(indexModel.getIndexName(), indexModel);
            }
        }

        schemaModel.getEntities().put(entity.getEntityName(), entity);
    }

    private void resolveComplexStructures(TypeElement typeElement, RoundEnvironment roundEnv) {
        EntityModel ownerEntity = schemaModel.getEntities().get(typeElement.getQualifiedName().toString());
        if (ownerEntity == null) return;

        // 상속 처리
        resolveInheritance(ownerEntity, typeElement, roundEnv);

        // 관계 처리
        for (Element field : typeElement.getEnclosedElements()) {
            if (field.getKind() != ElementKind.FIELD) continue;
            VariableElement variableField = (VariableElement) field;
            resolveFieldRelationshipsAndConstraints(variableField, ownerEntity);
        }
    }

    private void resolveFieldRelationshipsAndConstraints(VariableElement field, EntityModel ownerEntity) {
        ManyToOne manyToOne = field.getAnnotation(ManyToOne.class);
        OneToOne oneToOne = field.getAnnotation(OneToOne.class);
        ManyToMany manyToMany = field.getAnnotation(ManyToMany.class);

        // 관계 처리
        if (manyToOne != null || oneToOne != null) {
            processToOneRelationship(field, ownerEntity);
        } else if (manyToMany != null) {
            processManyToMany(field, ownerEntity);
        }

        // 필드 레벨 제약조건 처리
        processConstraints(field, field.getSimpleName().toString(), ownerEntity.getConstraints());
    }

    private void resolveInheritance(EntityModel entity, TypeElement typeElement, RoundEnvironment roundEnv) {
        Inheritance inheritance = typeElement.getAnnotation(Inheritance.class);
        if (inheritance == null) return;

        if (inheritance.strategy() == InheritanceType.SINGLE_TABLE) {
            entity.setInheritance("SINGLE_TABLE");
            DiscriminatorColumn discriminatorColumn = typeElement.getAnnotation(DiscriminatorColumn.class);
            if (discriminatorColumn != null) {
                ColumnModel dColumn = ColumnModel.builder()
                        .columnName(discriminatorColumn.name().isEmpty() ? "dtype" : discriminatorColumn.name())
                        .sqlType(MySQLDialect.mapDiscriminatorType(discriminatorColumn.discriminatorType()))
                        .javaType("java.lang.String")
                        .isPrimaryKey(false)
                        .isNullable(false)
                        .build();
                if (entity.getColumns().containsKey(dColumn.getColumnName())) {
                    processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                            "Duplicate column name: " + dColumn.getColumnName() + " in entity " + entity.getEntityName());
                    return;
                }
                entity.getColumns().put(dColumn.getColumnName(), dColumn);
            }
        } else if (inheritance.strategy() == InheritanceType.JOINED) {
            entity.setInheritance("JOINED");
            findAndProcessJoinedChildren(entity, typeElement, roundEnv);
        }
    }

    private void findAndProcessJoinedChildren(EntityModel parentEntity, TypeElement parentType, RoundEnvironment roundEnv) {
        String parentPkColumnName = findPrimaryKeyColumnName(parentEntity);
        ColumnModel parentPkColumn = parentEntity.getColumns().get(parentPkColumnName);
        if (parentPkColumn == null) {
            processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                    "Primary key not found for parent entity: " + parentEntity.getEntityName());
            return;
        }

        roundEnv.getElementsAnnotatedWith(Entity.class).stream()
                .filter(e -> e.getKind() == ElementKind.CLASS && !e.equals(parentType) && isSubType((TypeElement) e, parentType))
                .map(e -> (TypeElement) e)
                .forEach(childType -> {
                    EntityModel childEntity = schemaModel.getEntities().get(childType.getQualifiedName().toString());
                    if (childEntity == null) return;

                    childEntity.setParentEntity(parentEntity.getEntityName());
                    childEntity.setInheritance("JOINED");

                    ColumnModel idColumnAsFk = ColumnModel.builder()
                            .columnName(parentPkColumnName)
                            .javaType(parentPkColumn.getJavaType())
                            .sqlType(parentPkColumn.getSqlType())
                            .isPrimaryKey(true)
                            .isNullable(false)
                            .build();
                    if (childEntity.getColumns().containsKey(idColumnAsFk.getColumnName())) {
                        processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                                "Duplicate column name: " + idColumnAsFk.getColumnName() + " in child entity " + childEntity.getEntityName());
                        return;
                    }
                    childEntity.getColumns().put(idColumnAsFk.getColumnName(), idColumnAsFk);

                    RelationshipModel relationship = RelationshipModel.builder()
                            .type("JoinedInheritance")
                            .column(parentPkColumnName)
                            .referencedTable(parentEntity.getTableName())
                            .referencedColumn(parentPkColumnName)
                            .build();
                    childEntity.getRelationships().add(relationship);
                });
    }

    private void processToOneRelationship(VariableElement field, EntityModel ownerEntity) {
        JoinColumn joinColumn = field.getAnnotation(JoinColumn.class);
        if (joinColumn == null) return;

        TypeElement referencedTypeElement = getReferencedTypeElement(field.asType());
        if (referencedTypeElement == null) {
            processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                    "Cannot resolve referenced type for field: " + field.getSimpleName() + " in entity " + ownerEntity.getEntityName());
            return;
        }
        EntityModel referencedEntity = schemaModel.getEntities().get(referencedTypeElement.getQualifiedName().toString());
        if (referencedEntity == null) {
            processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                    "Referenced entity not found: " + referencedTypeElement.getQualifiedName() + " for field " + field.getSimpleName());
            return;
        }

        String referencedPkColumnName = joinColumn.referencedColumnName().isEmpty()
                ? findPrimaryKeyColumnName(referencedEntity)
                : joinColumn.referencedColumnName();
        ColumnModel referencedPkColumn = referencedEntity.getColumns().get(referencedPkColumnName);
        if (referencedPkColumn == null) {
            processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                    "Referenced primary key column not found: " + referencedPkColumnName + " in entity " + referencedEntity.getEntityName());
            return;
        }

        String fkColumnName = joinColumn.name().isEmpty() ? field.getSimpleName().toString() + "_" + referencedPkColumnName : joinColumn.name();
        MapsId mapsId = field.getAnnotation(MapsId.class);

        ColumnModel fkColumn = ColumnModel.builder()
                .columnName(fkColumnName)
                .javaType(referencedPkColumn.getJavaType())
                .sqlType(referencedPkColumn.getSqlType())
                .isPrimaryKey(mapsId != null)
                .isNullable(mapsId == null)
                .isUnique(field.getAnnotation(OneToOne.class) != null && mapsId == null)
                .build();
        if (ownerEntity.getColumns().containsKey(fkColumnName)) {
            processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                    "Duplicate column name: " + fkColumnName + " in entity " + ownerEntity.getEntityName());
            return;
        }
        ownerEntity.getColumns().put(fkColumnName, fkColumn);

        RelationshipModel relationship = RelationshipModel.builder()
                .type(field.getAnnotation(ManyToOne.class) != null ? "ManyToOne" : "OneToOne")
                .column(fkColumnName)
                .referencedTable(referencedEntity.getTableName())
                .referencedColumn(referencedPkColumnName)
                .mapsId(mapsId != null)
                .constraintName(joinColumn.name()) // FK 이름 포함
                .build();
        ownerEntity.getRelationships().add(relationship);
    }

    private void processManyToMany(VariableElement field, EntityModel ownerEntity) {
        JoinTable joinTable = field.getAnnotation(JoinTable.class);
        TypeElement referencedTypeElement = getReferencedTypeElement(field.asType());
        if (referencedTypeElement == null) {
            processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                    "Cannot resolve referenced type for field: " + field.getSimpleName() + " in entity " + ownerEntity.getEntityName());
            return;
        }
        EntityModel referencedEntity = schemaModel.getEntities().get(referencedTypeElement.getQualifiedName().toString());
        if (referencedEntity == null) {
            processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                    "Referenced entity not found: " + referencedTypeElement.getQualifiedName() + " for field " + field.getSimpleName());
            return;
        }

        String ownerTable = ownerEntity.getTableName();
        String referencedTable = referencedEntity.getTableName();
        String ownerPkColumnName = findPrimaryKeyColumnName(ownerEntity);
        String inversePkColumnName = findPrimaryKeyColumnName(referencedEntity);
        ColumnModel ownerPkColumn = ownerEntity.getColumns().get(ownerPkColumnName);
        ColumnModel inversePkColumn = referencedEntity.getColumns().get(inversePkColumnName);
        if (ownerPkColumn == null || inversePkColumn == null) {
            processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                    "Primary key column not found for owner or referenced entity: " + ownerEntity.getEntityName() + "/" + referencedEntity.getEntityName());
            return;
        }

        String joinTableName;
        String ownerFkColumn;
        String inverseFkColumn;

        if (joinTable != null) {
            joinTableName = joinTable.name().isEmpty() ? ownerTable + "_" + referencedTable : joinTable.name();
            ownerFkColumn = joinTable.joinColumns().length > 0 && !joinTable.joinColumns()[0].name().isEmpty()
                    ? joinTable.joinColumns()[0].name() : ownerTable.toLowerCase() + "_" + ownerPkColumnName;
            inverseFkColumn = joinTable.inverseJoinColumns().length > 0 && !joinTable.inverseJoinColumns()[0].name().isEmpty()
                    ? joinTable.inverseJoinColumns()[0].name() : referencedTable.toLowerCase() + "_" + inversePkColumnName;
        } else {
            joinTableName = ownerTable + "_" + referencedTable;
            ownerFkColumn = ownerTable.toLowerCase() + "_" + ownerPkColumnName;
            inverseFkColumn = referencedTable.toLowerCase() + "_" + inversePkColumnName;
        }

        if (schemaModel.getEntities().containsKey(joinTableName)) {
            return;
        }

        EntityModel joinTableEntity = EntityModel.builder()
                .entityName(joinTableName)
                .tableName(joinTableName)
                .isJoinTable(true)
                .build();

        ColumnModel ownerFkColumnModel = ColumnModel.builder()
                .columnName(ownerFkColumn)
                .javaType(ownerPkColumn.getJavaType())
                .sqlType(ownerPkColumn.getSqlType())
                .isPrimaryKey(true)
                .isNullable(false)
                .build();
        joinTableEntity.getColumns().put(ownerFkColumn, ownerFkColumnModel);

        ColumnModel inverseFkColumnModel = ColumnModel.builder()
                .columnName(inverseFkColumn)
                .javaType(inversePkColumn.getJavaType())
                .sqlType(inversePkColumn.getSqlType())
                .isPrimaryKey(true)
                .isNullable(false)
                .build();
        joinTableEntity.getColumns().put(inverseFkColumn, inverseFkColumnModel);

        RelationshipModel ownerRelationship = RelationshipModel.builder()
                .type("ManyToMany")
                .column(ownerFkColumn)
                .referencedTable(ownerTable)
                .referencedColumn(ownerPkColumnName)
                .constraintName(ownerFkColumn)
                .build();
        joinTableEntity.getRelationships().add(ownerRelationship);

        RelationshipModel inverseRelationship = RelationshipModel.builder()
                .type("ManyToMany")
                .column(inverseFkColumn)
                .referencedTable(referencedTable)
                .referencedColumn(inversePkColumnName)
                .constraintName(inverseFkColumn)
                .build();
        joinTableEntity.getRelationships().add(inverseRelationship);

        processConstraints(field, null, joinTableEntity.getConstraints());
        schemaModel.getEntities().put(joinTableEntity.getEntityName(), joinTableEntity);
    }

    private void processEmbedded(VariableElement field, Map<String, ColumnModel> columns, List<ConstraintModel> constraints, Set<String> processedTypes) {
        TypeMirror typeMirror = field.asType();
        if (!(typeMirror instanceof DeclaredType)) return;
        TypeElement embeddableType = (TypeElement) ((DeclaredType) typeMirror).asElement();
        if (embeddableType.getAnnotation(Embeddable.class) == null) return;

        String typeName = embeddableType.getQualifiedName().toString();
        if (processedTypes.contains(typeName)) {
            processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                    "Cyclic embedded type detected: " + typeName);
            return;
        }
        processedTypes.add(typeName);

        AttributeOverrides overrides = field.getAnnotation(AttributeOverrides.class);
        Map<String, String> columnOverrides = new HashMap<>();
        if (overrides != null) {
            for (AttributeOverride override : overrides.value()) {
                columnOverrides.put(override.name(), override.column().name());
            }
        }

        for (Element enclosed : embeddableType.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.FIELD) continue;
            VariableElement embeddedField = (VariableElement) enclosed;
            Column column = embeddedField.getAnnotation(Column.class);
            Embedded nestedEmbedded = embeddedField.getAnnotation(Embedded.class);

            if (nestedEmbedded != null) {
                processEmbedded(embeddedField, columns, constraints, processedTypes);
                continue;
            }

            if (column != null) {
                String columnName = columnOverrides.getOrDefault(embeddedField.getSimpleName().toString(),
                        column.name().isEmpty() ? embeddedField.getSimpleName().toString() : column.name());
                ColumnModel columnModel = ColumnModel.builder()
                        .columnName(columnName)
                        .javaType(embeddedField.asType().toString())
                        .sqlType(MySQLDialect.mapJavaTypeToSqlType(embeddedField.asType().toString(), column))
                        .isPrimaryKey(false)
                        .isNullable(column.nullable())
                        .isUnique(column.unique())
                        .length(column.length())
                        .precision(column.precision())
                        .scale(column.scale())
                        .defaultValue(column.columnDefinition().isEmpty() ? null : column.columnDefinition())
                        .build();
                if (columns.containsKey(columnModel.getColumnName())) {
                    processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                            "Duplicate column name in embedded type: " + columnModel.getColumnName());
                    return;
                }
                columns.put(columnModel.getColumnName(), columnModel);

                processConstraints(embeddedField, columnModel.getColumnName(), constraints);
            }
        }

        processedTypes.remove(typeName);
    }

    private void processConstraints(Element element, String fieldName, List<ConstraintModel> constraints) {
        Constraint constraint = element.getAnnotation(Constraint.class);
        Constraints constraintsAnno = element.getAnnotation(Constraints.class);

        List<Constraint> constraintList = new ArrayList<>();
        if (constraint != null) {
            constraintList.add(constraint);
        }
        if (constraintsAnno != null) {
            constraintList.addAll(Arrays.asList(constraintsAnno.value()));
        }

        if (constraintList.isEmpty()) return;

        for (Constraint c : constraintList) {
            ConstraintType type = c.type();
            if (type == ConstraintType.AUTO) {
                type = inferConstraintType(element, fieldName);
                if (type == null) {
                    processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                            "Cannot infer constraint type for " + element.getSimpleName() + ". Please specify explicit type.");
                    continue;
                }
            }

            if (type == ConstraintType.CHECK) {
                processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.WARNING,
                        "CHECK constraints may not be enforced in MySQL versions prior to 8.0.16 for constraint: " + c.value());
            }

            ConstraintModel constraintModel = ConstraintModel.builder()
                    .name(c.value())
                    .type(type)
                    .column(fieldName)
                    .build();

            if (type == ConstraintType.FOREIGN_KEY && element.getKind() == ElementKind.FIELD) {
                VariableElement field = (VariableElement) element;
                TypeElement referencedTypeElement = getReferencedTypeElement(field.asType());
                if (referencedTypeElement != null) {
                    EntityModel referencedEntity = schemaModel.getEntities().get(referencedTypeElement.getQualifiedName().toString());
                    if (referencedEntity != null) {
                        String referencedPkColumnName = findPrimaryKeyColumnName(referencedEntity);
                        constraintModel.setReferencedTable(referencedEntity.getTableName());
                        constraintModel.setReferencedColumn(referencedPkColumnName);
                        constraintModel.setName(c.value().isEmpty() ? "fk_" + fieldName : c.value());
                    } else {
                        processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                                "Referenced entity not found for FOREIGN_KEY constraint: " + c.value());
                        continue;
                    }
                } else {
                    processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                            "Cannot resolve referenced type for FOREIGN_KEY constraint: " + c.value());
                    continue;
                }
            }

            if (c.onDelete() != OnDeleteAction.NO_ACTION) {
                constraintModel.setOnDelete(c.onDelete());
            }
            if (c.onUpdate() != OnUpdateAction.NO_ACTION) {
                constraintModel.setOnUpdate(c.onUpdate());
            }

            constraints.add(constraintModel);
        }
    }

    private ConstraintType inferConstraintType(Element element, String fieldName) {
        if (element.getKind() == ElementKind.FIELD) {
            VariableElement field = (VariableElement) element;
            if (field.getAnnotation(ManyToOne.class) != null || field.getAnnotation(OneToOne.class) != null || field.getAnnotation(JoinColumn.class) != null) {
                return ConstraintType.FOREIGN_KEY;
            }
            if (field.getAnnotation(Column.class) != null && field.getAnnotation(Column.class).unique()) {
                return ConstraintType.UNIQUE;
            }
        }
        return null;
    }

    private String findPrimaryKeyColumnName(EntityModel entityModel) {
        return entityModel.getColumns().values().stream()
                .filter(ColumnModel::isPrimaryKey)
                .map(ColumnModel::getColumnName)
                .findFirst()
                .orElse("id");
    }

    private TypeElement getReferencedTypeElement(TypeMirror typeMirror) {
        if (typeMirror instanceof DeclaredType) {
            Element element = ((DeclaredType) typeMirror).asElement();
            if (element instanceof TypeElement) {
                return (TypeElement) element;
            }
        }
        processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                "Cannot resolve type element for type: " + typeMirror);
        return null;
    }

    private void saveModelToJson() {
        try {
            String fileName = "jinx/schema-" + schemaModel.getVersion() + ".json";
            FileObject file = processingEnv.getFiler()
                    .createResource(StandardLocation.CLASS_OUTPUT,
                            /* pkg */ "",
                            fileName);

            try (Writer writer = file.openWriter()) {
                OBJECT_MAPPER.writeValue(writer, schemaModel);
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                    "Failed to write schema file: " + e.getMessage());
        }
    }

    private boolean isSubType(TypeElement child, TypeElement parent) {
        if (child == null || parent == null) {
            processingEnv.getMessager().printMessage(
                    javax.tools.Diagnostic.Kind.ERROR,
                    "Invalid TypeElement: child or parent is null"
            );
            return false;
        }

        TypeMirror childType = child.asType();
        TypeMirror parentType = parent.asType();

        if (childType == null || parentType == null) {
            processingEnv.getMessager().printMessage(
                    javax.tools.Diagnostic.Kind.ERROR,
                    "Invalid TypeMirror for child: " + child.getSimpleName() +
                            " or parent: " + parent.getSimpleName()
            );
            return false;
        }

        boolean isSubtype = processingEnv.getTypeUtils().isSubtype(childType, parentType);
        if (isSubtype) {
            processingEnv.getMessager().printMessage(
                    javax.tools.Diagnostic.Kind.NOTE,
                    child.getSimpleName() + " is a subtype of " + parent.getSimpleName()
            );
        }
        return isSubtype;
    }
}