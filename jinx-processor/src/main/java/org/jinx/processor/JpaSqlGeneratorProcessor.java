package org.jinx.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.auto.service.AutoService;
import jakarta.persistence.*;
import org.jinx.annotation.*;
import org.jinx.model.*;
import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
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
        "jakarta.persistence.SequenceGenerator",
        "jakarta.persistence.SequenceGenerators",
        "jakarta.persistence.TableGenerator",
        "jakarta.persistence.Transient",
        "jakarta.persistence.Enumerated",
        "org.jinx.annotation.Constraint",
        "org.jinx.annotation.Constraints",
        "org.jinx.annotation.Identity"
})
@SupportedSourceVersion(SourceVersion.RELEASE_17)
public class JpaSqlGeneratorProcessor extends AbstractProcessor {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private SchemaModel schemaModel;

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) {
            schemaModel = SchemaModel.builder()
                    .version(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")))
                    .build();

            for (Element element : roundEnv.getElementsAnnotatedWith(Entity.class)) {
                if (element.getKind() == ElementKind.CLASS) {
                    buildBasicEntityModel((TypeElement) element);
                }
            }

            for (Element element : roundEnv.getElementsAnnotatedWith(Entity.class)) {
                if (element.getKind() == ElementKind.CLASS) {
                    resolveComplexStructures((TypeElement) element, roundEnv);
                }
            }

            saveModelToJson();
            return true;
        }
        return false;
    }

    private void buildBasicEntityModel(TypeElement typeElement) {
        Table table = typeElement.getAnnotation(Table.class);
        String tableName = table != null && !table.name().isEmpty() ? table.name() : typeElement.getSimpleName().toString();
        String entityName = typeElement.getQualifiedName().toString();

        if (schemaModel.getEntities().containsKey(entityName)) {
            processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                    "Duplicate entity found: " + entityName, typeElement);
            return;
        }

        EntityModel entity = EntityModel.builder()
                .entityName(entityName)
                .tableName(tableName)
                .build();

        processClassLevelSequenceGenerators(typeElement);
        processConstraints(typeElement, null, entity.getConstraints());

        for (Element enclosed : typeElement.getEnclosedElements()) {
            if (enclosed.getAnnotation(Transient.class) != null ||
                    enclosed.getModifiers().contains(Modifier.TRANSIENT)) {
                continue;
            }
            if (enclosed.getKind() != ElementKind.FIELD) continue;
            VariableElement field = (VariableElement) enclosed;
            Column column = field.getAnnotation(Column.class);
            Embedded embedded = field.getAnnotation(Embedded.class);
            Identity identity = field.getAnnotation(Identity.class);

            if (embedded != null) {
                processEmbedded(field, entity.getColumns(), entity.getConstraints(), new HashSet<>());
                continue;
            }

            if (column != null) {
                ColumnModel.ColumnModelBuilder columnBuilder = ColumnModel.builder()
                        .columnName(column.name().isEmpty() ? field.getSimpleName().toString() : column.name())
                        .javaType(field.asType().toString())
                        .isPrimaryKey(field.getAnnotation(Id.class) != null)
                        .isNullable(column.nullable())
                        .isUnique(column.unique())
                        .length(column.length())
                        .precision(column.precision())
                        .scale(column.scale())
                        .defaultValue(column.columnDefinition().isEmpty() ? null : column.columnDefinition())
                        .generationStrategy(GenerationStrategy.NONE)
                        .identityStartValue(1)
                        .identityIncrement(1)
                        .identityCache(0)
                        .identityMinValue(Long.MIN_VALUE)
                        .identityMaxValue(Long.MAX_VALUE)
                        .identityOptions(new String[]{})
                        .enumStringMapping(false)
                        .enumValues(new String[]{});


                Enumerated enumerated = field.getAnnotation(Enumerated.class);
                if (enumerated != null) {
                    columnBuilder.enumStringMapping(enumerated.value() == EnumType.STRING);
                    columnBuilder.enumValues(getEnumConstants(field.asType()));
                }

                GeneratedValue gv = field.getAnnotation(GeneratedValue.class);
                if (identity != null) {
                    columnBuilder.generationStrategy(GenerationStrategy.IDENTITY)
                            .identityStartValue(identity.start())
                            .identityIncrement(identity.increment())
                            .identityCache(identity.cache())
                            .identityMinValue(identity.min())
                            .identityMaxValue(identity.max())
                            .identityOptions(identity.options());
                } else if (gv != null) {
                    switch (gv.strategy()) {
                        case IDENTITY, AUTO:
                            columnBuilder.generationStrategy(GenerationStrategy.IDENTITY);
                            break;

                        case SEQUENCE:
                            columnBuilder.generationStrategy(GenerationStrategy.SEQUENCE);
                            String generatorName = gv.generator();
                            if (generatorName.isBlank()) {
                                processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                                        "@GeneratedValue(strategy=SEQUENCE) must specify a 'generator'", field);
                                continue;
                            }

                            SequenceGenerator sg = field.getAnnotation(SequenceGenerator.class);
                            if (sg != null) {
                                if (!sg.name().equals(generatorName)) {
                                    processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                                            "GeneratedValue generator '" + generatorName + "' does not match SequenceGenerator name '" + sg.name() + "'", field);
                                    continue;
                                }
                                schemaModel.getSequences().computeIfAbsent(sg.name(), key ->
                                        SequenceModel.builder()
                                                .name(sg.sequenceName().isBlank() ? key : sg.sequenceName())
                                                .initialValue(sg.initialValue())
                                                .allocationSize(sg.allocationSize())
                                                .build()
                                );
                            }

                            if (!schemaModel.getSequences().containsKey(generatorName)) {
                                processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                                        "SequenceGenerator '" + generatorName + "' is not defined in the entity or its fields.", field);
                                continue;
                            }
                            columnBuilder.sequenceName(generatorName);
                            break;

                        case TABLE:
                            columnBuilder.generationStrategy(GenerationStrategy.TABLE);
                            TableGenerator tg = field.getAnnotation(TableGenerator.class);
                            if (tg != null) {
                                schemaModel.getTableGenerators().computeIfAbsent(tg.name(), key ->
                                        TableGeneratorModel.builder()
                                                .name(tg.name())
                                                .table(tg.table().isEmpty() ? "sequence_table" : tg.table())
                                                .pkColumnName(tg.pkColumnName().isEmpty() ? "pk_column" : tg.pkColumnName())
                                                .valueColumnName(tg.valueColumnName().isEmpty() ? "value_column" : tg.valueColumnName())
                                                .pkColumnValue(tg.pkColumnValue())
                                                .initialValue(tg.initialValue())
                                                .allocationSize(tg.allocationSize())
                                                .build()
                                );
                                columnBuilder.tableGeneratorName(tg.name());
                            }
                            break;
                    }
                } else if (columnBuilder.build().isPrimaryKey()) {
                    columnBuilder.isManualPrimaryKey(true);
                }

                ColumnModel builtColumn = columnBuilder.build();
                String colName = builtColumn.getColumnName();
                if (entity.getColumns().containsKey(colName)) {
                    processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                            "Duplicate column name '" + colName + "' in entity " + entityName, field);
                    continue;
                }
                entity.getColumns().putIfAbsent(colName, builtColumn);
                processConstraints(field, colName, entity.getConstraints());
            }
        }

        if (table != null) {
            for (Index index : table.indexes()) {
                String indexName = index.name();
                if (entity.getIndexes().containsKey(indexName)) {
                    processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                            "Duplicate index name '" + indexName + "' in entity " + entityName, typeElement);
                    continue;
                }
                IndexModel indexModel = IndexModel.builder()
                        .indexName(indexName)
                        .columnNames(Arrays.asList(index.columnList().split(",\\s*")))
                        .isUnique(index.unique())
                        .build();
                entity.getIndexes().put(indexModel.getIndexName(), indexModel);
            }
        }

        schemaModel.getEntities().putIfAbsent(entity.getEntityName(), entity);
    }

    private String[] getEnumConstants(TypeMirror tm) {
        if (!(tm instanceof DeclaredType dt)) return new String[]{};
        Element e = dt.asElement();
        if (e.getKind() != ElementKind.ENUM) return new String[]{};

        return e.getEnclosedElements().stream()
                .filter(el -> el.getKind() == ElementKind.ENUM_CONSTANT)
                .map(Element::getSimpleName)
                .map(Object::toString)
                .toArray(String[]::new);
    }

    private void processClassLevelSequenceGenerators(TypeElement typeElement) {
        List<SequenceGenerator> sequenceGenerators = new ArrayList<>();

        SequenceGenerator single = typeElement.getAnnotation(SequenceGenerator.class);
        if (single != null) {
            sequenceGenerators.add(single);
        }

        SequenceGenerators multiple = typeElement.getAnnotation(SequenceGenerators.class);
        if (multiple != null) {
            sequenceGenerators.addAll(Arrays.asList(multiple.value()));
        }

        for (SequenceGenerator sg : sequenceGenerators) {
            if (sg.name().isBlank()) {
                processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                        "SequenceGenerator must have a non-blank name.", typeElement);
                continue;
            }

            if (schemaModel.getSequences().containsKey(sg.name())) {
                processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                        "Duplicate SequenceGenerator name '" + sg.name() + "'", typeElement);
                continue;
            }

            schemaModel.getSequences().computeIfAbsent(sg.name(), key ->
                    SequenceModel.builder()
                            .name(sg.sequenceName().isBlank() ? key : sg.sequenceName())
                            .initialValue(sg.initialValue())
                            .allocationSize(sg.allocationSize())
                            .build()
            );
        }
    }

    private void resolveComplexStructures(TypeElement typeElement, RoundEnvironment roundEnv) {
        EntityModel ownerEntity = schemaModel.getEntities().get(typeElement.getQualifiedName().toString());
        if (ownerEntity == null) return;

        resolveInheritance(ownerEntity, typeElement, roundEnv);

        for (Element field : typeElement.getEnclosedElements()) {
            if (field.getKind() != ElementKind.FIELD) continue;
            if (field.getAnnotation(Transient.class) != null ||
                    field.getModifiers().contains(Modifier.TRANSIENT)) {
                continue;
            }
            VariableElement variableField = (VariableElement) field;
            resolveFieldRelationshipsAndConstraints(variableField, ownerEntity);
        }
    }

    private void resolveFieldRelationshipsAndConstraints(VariableElement field, EntityModel ownerEntity) {
        ManyToOne manyToOne = field.getAnnotation(ManyToOne.class);
        OneToOne oneToOne = field.getAnnotation(OneToOne.class);
        ManyToMany manyToMany = field.getAnnotation(ManyToMany.class);

        if (manyToOne != null || oneToOne != null) {
            processToOneRelationship(field, ownerEntity);
        } else if (manyToMany != null) {
            processManyToMany(field, ownerEntity);
        }

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
                        .javaType("java.lang.String")
                        .isPrimaryKey(false)
                        .isNullable(false)
                        .generationStrategy(GenerationStrategy.NONE)
                        .build();
                if (entity.getColumns().containsKey(dColumn.getColumnName())) {
                    processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                            "Duplicate column name '" + dColumn.getColumnName() + "' for discriminator in entity " + entity.getEntityName(), typeElement);
                    return;
                }
                entity.getColumns().putIfAbsent(dColumn.getColumnName(), dColumn);
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
                            .isPrimaryKey(true)
                            .isNullable(false)
                            .build();
                    if (childEntity.getColumns().containsKey(idColumnAsFk.getColumnName())) {
                        processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                                "Duplicate column name '" + idColumnAsFk.getColumnName() + "' in child entity " + childEntity.getEntityName(), childType);
                        return;
                    }
                    childEntity.getColumns().putIfAbsent(idColumnAsFk.getColumnName(), idColumnAsFk);

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
        if (referencedTypeElement == null) return;
        EntityModel referencedEntity = schemaModel.getEntities().get(referencedTypeElement.getQualifiedName().toString());
        if (referencedEntity == null) return;

        String referencedPkColumnName = joinColumn.referencedColumnName().isEmpty()
                ? findPrimaryKeyColumnName(referencedEntity)
                : joinColumn.referencedColumnName();
        ColumnModel referencedPkColumn = referencedEntity.getColumns().get(referencedPkColumnName);
        if (referencedPkColumn == null) return;

        String fkColumnName = joinColumn.name().isEmpty() ? field.getSimpleName().toString() + "_" + referencedPkColumnName : joinColumn.name();
        MapsId mapsId = field.getAnnotation(MapsId.class);

        ColumnModel fkColumn = ColumnModel.builder()
                .columnName(fkColumnName)
                .javaType(referencedPkColumn.getJavaType())
                .isPrimaryKey(mapsId != null)
                .isNullable(mapsId == null)
                .isUnique(field.getAnnotation(OneToOne.class) != null && mapsId == null)
                .generationStrategy(GenerationStrategy.NONE)
                .build();
        if (ownerEntity.getColumns().containsKey(fkColumnName)) {
            processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                    "Duplicate column name '" + fkColumnName + "' in entity " + ownerEntity.getEntityName(), field);
            return;
        }
        ownerEntity.getColumns().putIfAbsent(fkColumnName, fkColumn);

        RelationshipModel relationship = RelationshipModel.builder()
                .type(field.getAnnotation(ManyToOne.class) != null ? "ManyToOne" : "OneToOne")
                .column(fkColumnName)
                .referencedTable(referencedEntity.getTableName())
                .referencedColumn(referencedPkColumnName)
                .mapsId(mapsId != null)
                .constraintName(joinColumn.name())
                .build();
        ownerEntity.getRelationships().add(relationship);
    }

    private void processManyToMany(VariableElement field, EntityModel ownerEntity) {
        JoinTable joinTable = field.getAnnotation(JoinTable.class);
        TypeElement referencedTypeElement = getReferencedTypeElement(field.asType());
        if (referencedTypeElement == null) return;
        EntityModel referencedEntity = schemaModel.getEntities().get(referencedTypeElement.getQualifiedName().toString());
        if (referencedEntity == null) return;

        String ownerTable = ownerEntity.getTableName();
        String referencedTable = referencedEntity.getTableName();
        String ownerPkColumnName = findPrimaryKeyColumnName(ownerEntity);
        String inversePkColumnName = findPrimaryKeyColumnName(referencedEntity);
        ColumnModel ownerPkColumn = ownerEntity.getColumns().get(ownerPkColumnName);
        ColumnModel inversePkColumn = referencedEntity.getColumns().get(inversePkColumnName);
        if (ownerPkColumn == null || inversePkColumn == null) return;

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
            processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                    "Duplicate join table entity '" + joinTableName + "'", field);
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
                .isPrimaryKey(true)
                .generationStrategy(GenerationStrategy.NONE)
                .isNullable(false)
                .build();
        joinTableEntity.getColumns().putIfAbsent(ownerFkColumn, ownerFkColumnModel);

        ColumnModel inverseFkColumnModel = ColumnModel.builder()
                .columnName(inverseFkColumn)
                .javaType(inversePkColumn.getJavaType())
                .isPrimaryKey(true)
                .generationStrategy(GenerationStrategy.NONE)
                .isNullable(false)
                .build();
        joinTableEntity.getColumns().putIfAbsent(inverseFkColumn, inverseFkColumnModel);

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
        schemaModel.getEntities().putIfAbsent(joinTableEntity.getEntityName(), joinTableEntity);
    }

    private void processEmbedded(VariableElement field, Map<String, ColumnModel> columns, List<ConstraintModel> constraints, Set<String> processedTypes) {
        TypeMirror typeMirror = field.asType();
        if (!(typeMirror instanceof DeclaredType)) return;
        TypeElement embeddableType = (TypeElement) ((DeclaredType) typeMirror).asElement();
        if (embeddableType.getAnnotation(Embeddable.class) == null) return;

        String typeName = embeddableType.getQualifiedName().toString();
        if (processedTypes.contains(typeName)) return;
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
            Identity identity = embeddedField.getAnnotation(Identity.class);

            if (nestedEmbedded != null) {
                processEmbedded(embeddedField, columns, constraints, processedTypes);
                continue;
            }

            if (column != null) {
                String columnName = columnOverrides.getOrDefault(embeddedField.getSimpleName().toString(),
                        column.name().isEmpty() ? embeddedField.getSimpleName().toString() : column.name());
                ColumnModel.ColumnModelBuilder columnBuilder = ColumnModel.builder()
                        .columnName(columnName)
                        .javaType(embeddedField.asType().toString())
                        .isPrimaryKey(embeddedField.getAnnotation(Id.class) != null)
                        .isNullable(column.nullable())
                        .isUnique(column.unique())
                        .length(column.length())
                        .precision(column.precision())
                        .scale(column.scale())
                        .generationStrategy(GenerationStrategy.NONE)
                        .defaultValue(column.columnDefinition().isEmpty() ? null : column.columnDefinition())
                        .identityStartValue(1)
                        .identityIncrement(1)
                        .identityCache(0)
                        .identityMinValue(Long.MIN_VALUE)
                        .identityMaxValue(Long.MAX_VALUE)
                        .identityOptions(new String[]{});

                if (identity != null) {
                    columnBuilder.generationStrategy(GenerationStrategy.IDENTITY)
                            .identityStartValue(identity.start())
                            .identityIncrement(identity.increment())
                            .identityCache(identity.cache())
                            .identityMinValue(identity.min())
                            .identityMaxValue(identity.max())
                            .identityOptions(identity.options());
                } else if (embeddedField.getAnnotation(GeneratedValue.class) != null) {
                    GeneratedValue gv = embeddedField.getAnnotation(GeneratedValue.class);
                    switch (gv.strategy()) {
                        case IDENTITY, AUTO:
                            columnBuilder.generationStrategy(GenerationStrategy.IDENTITY);
                            break;
                        case SEQUENCE:
                            columnBuilder.generationStrategy(GenerationStrategy.SEQUENCE);
                            String generatorName = gv.generator();
                            if (generatorName.isBlank()) {
                                processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                                        "@GeneratedValue(strategy=SEQUENCE) must specify a 'generator' in embedded field", embeddedField);
                                continue;
                            }
                            // Sequence handling for embedded fields (simplified)
                            SequenceGenerator sg = embeddedField.getAnnotation(SequenceGenerator.class);
                            if (sg != null && sg.name().equals(generatorName)) {
                                schemaModel.getSequences().computeIfAbsent(sg.name(), key ->
                                        SequenceModel.builder()
                                                .name(sg.sequenceName().isBlank() ? key : sg.sequenceName())
                                                .initialValue(sg.initialValue())
                                                .allocationSize(sg.allocationSize())
                                                .build()
                                );
                                columnBuilder.sequenceName(generatorName);
                            }
                            break;
                        case TABLE:
                            columnBuilder.generationStrategy(GenerationStrategy.TABLE);
                            TableGenerator tg = embeddedField.getAnnotation(TableGenerator.class);
                            if (tg != null) {
                                schemaModel.getTableGenerators().computeIfAbsent(tg.name(), key ->
                                        TableGeneratorModel.builder()
                                                .name(tg.name())
                                                .table(tg.table().isEmpty() ? "sequence_table" : tg.table())
                                                .pkColumnName(tg.pkColumnName().isEmpty() ? "pk_column" : tg.pkColumnName())
                                                .valueColumnName(tg.valueColumnName().isEmpty() ? "value_column" : tg.valueColumnName())
                                                .pkColumnValue(tg.pkColumnValue())
                                                .initialValue(tg.initialValue())
                                                .allocationSize(tg.allocationSize())
                                                .build()
                                );
                                columnBuilder.tableGeneratorName(tg.name());
                            }
                            break;
                    }
                } else if (columnBuilder.build().isPrimaryKey()) {
                    columnBuilder.isManualPrimaryKey(true);
                }

                ColumnModel columnModel = columnBuilder.build();
                if (columns.containsKey(columnModel.getColumnName())) {
                    processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                            "Duplicate embedded column name '" + columnModel.getColumnName() + "'", embeddedField);
                    continue;
                }
                columns.putIfAbsent(columnModel.getColumnName(), columnModel);
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
                if (type == null) continue;
            }

            String checkExpr = null;
            if (type == ConstraintType.CHECK) {
                checkExpr = c.expression();
                if (checkExpr.isBlank()) continue;
            }

            ConstraintModel constraintModel = ConstraintModel.builder()
                    .name(c.value())
                    .type(type)
                    .column(fieldName)
                    .checkClause(checkExpr)
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
                        continue;
                    }
                } else {
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
        return null;
    }

    private void saveModelToJson() {
        try {
            String fileName = "jinx/schema-" + schemaModel.getVersion() + ".json";
            FileObject file = processingEnv.getFiler()
                    .createResource(StandardLocation.CLASS_OUTPUT, "", fileName);

            try (Writer writer = file.openWriter()) {
                OBJECT_MAPPER.writeValue(writer, schemaModel);
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                    "Failed to write schema file: " + e.getMessage());
        }
    }

    private boolean isSubType(TypeElement child, TypeElement parent) {
        if (child == null || parent == null) return false;
        TypeMirror childType = child.asType();
        TypeMirror parentType = parent.asType();
        if (childType == null || parentType == null) return false;
        return processingEnv.getTypeUtils().isSubtype(childType, parentType);
    }
}