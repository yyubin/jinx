package org.jinx.handler;

import jakarta.persistence.*;
import org.jinx.context.ProcessingContext;
import org.jinx.model.*;

import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.util.*;
import java.util.stream.Collectors;

public class EntityHandler {
    private final ProcessingContext context;
    private final ColumnHandler columnHandler;
    private final EmbeddedHandler embeddedHandler;
    private final ConstraintHandler constraintHandler;
    private final SequenceHandler sequenceHandler;
    private final ElementCollectionHandler elementCollectionHandler;
    private final TableGeneratorHandler tableGeneratorHandler;

    public EntityHandler(ProcessingContext context, ColumnHandler columnHandler, EmbeddedHandler embeddedHandler, ConstraintHandler constraintHandler, SequenceHandler sequenceHandler, ElementCollectionHandler elementCollectionHandler, TableGeneratorHandler tableGeneratorHandler) {
        this.context = context;
        this.columnHandler = columnHandler;
        this.embeddedHandler = embeddedHandler;
        this.constraintHandler = constraintHandler;
        this.sequenceHandler = sequenceHandler;
        this.elementCollectionHandler = elementCollectionHandler;
        this.tableGeneratorHandler = tableGeneratorHandler;
    }

    public void handle(TypeElement typeElement) {
        Optional<Table> tableOpt = Optional.ofNullable(typeElement.getAnnotation(Table.class));
        String tableName = tableOpt.map(Table::name).filter(n -> !n.isEmpty()).orElse(typeElement.getSimpleName().toString());
        String schema = tableOpt.map(Table::schema).filter(s -> !s.isEmpty()).orElse(null);
        String catalog = tableOpt.map(Table::catalog).filter(c -> !c.isEmpty()).orElse(null);
        String entityName = typeElement.getQualifiedName().toString();

        if (context.getSchemaModel().getEntities().containsKey(entityName)) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Duplicate entity found: " + entityName, typeElement);
            EntityModel invalidEntity = EntityModel.builder().entityName(entityName).isValid(false).build();
            context.getSchemaModel().getEntities().putIfAbsent(entityName, invalidEntity);
            return;
        }

        EntityModel entity = EntityModel.builder()
                .entityName(entityName)
                .tableName(tableName)
                .schema(schema)
                .catalog(catalog)
                .isValid(true)
                .build();

        sequenceHandler.processSequenceGenerators(typeElement);

        tableOpt.ifPresent(table -> {
            for (UniqueConstraint uc : table.uniqueConstraints()) {
                ConstraintModel constraint = ConstraintModel.builder()
                        .name(uc.name().isBlank() ? "uc_" + tableName + "_" + String.join("_", uc.columnNames()) : uc.name())
                        .type(ConstraintType.UNIQUE)
                        .columns(Arrays.asList(uc.columnNames()))
                        .build();
                entity.getConstraints().add(constraint);
            }
        });

        tableGeneratorHandler.processTableGenerators(typeElement);

        processConstraints(typeElement, null, entity.getConstraints(), entity.getTableName());

        // Handle @SecondaryTable(s)
        SecondaryTable secondaryTable = typeElement.getAnnotation(SecondaryTable.class);
        SecondaryTables secondaryTables = typeElement.getAnnotation(SecondaryTables.class);
        Map<String, SecondaryTable> tableMappings = new HashMap<>();
        tableMappings.put(entity.getTableName(), null); // Primary table
        List<SecondaryTable> secondaryTableList = new ArrayList<>();
        if (secondaryTable != null) secondaryTableList.add(secondaryTable);
        if (secondaryTables != null) secondaryTableList.addAll(Arrays.asList(secondaryTables.value()));
        for (SecondaryTable st : secondaryTableList) {
            tableMappings.put(st.name(), st);
            Optional<String> pkColumnName = context.findPrimaryKeyColumnName(entity);
            if (pkColumnName.isPresent() && st.pkJoinColumns().length > 0) {
                String fkColumnName = st.pkJoinColumns()[0].name();
                RelationshipModel fkRelationship = RelationshipModel.builder()
                        .type(RelationshipType.SECONDARY_TABLE)
                        .columns(List.of(fkColumnName))
                        .referencedTable(entity.getTableName())
                        .referencedColumns(List.of(pkColumnName.get()))
                        .constraintName("fk_" + st.name() + "_" + fkColumnName)
                        .build();
                entity.getRelationships().add(fkRelationship);
            }
        }

        List<TypeElement> superclasses = getMappedSuperclasses(typeElement);
        for (TypeElement superclass : superclasses) {
            processMappedSuperclass(superclass, entity);
        }

        IdClass idClass = typeElement.getAnnotation(IdClass.class);
        EmbeddedId embeddedId = typeElement.getAnnotation(EmbeddedId.class);
        if (idClass != null) {
            List<VariableElement> idFields = getIdFields(typeElement);
            for (VariableElement idField : idFields) {
                ColumnModel column = columnHandler.createFrom(idField, Collections.emptyMap());
                if (column != null) {
                    column.setPrimaryKey(true);
                    entity.getColumns().putIfAbsent(column.getColumnName(), column);
                }
            }
        } else if (embeddedId != null) {
            VariableElement embeddedIdField = getEmbeddedIdField(typeElement);
            if (embeddedIdField != null) {
                processEmbeddedId(embeddedIdField, entity);
            }
        }

        for (Element enclosed : typeElement.getEnclosedElements()) {
            if (enclosed.getAnnotation(Transient.class) != null ||
                    enclosed.getModifiers().contains(Modifier.TRANSIENT) ||
                    enclosed.getKind() != ElementKind.FIELD) {
                continue;
            }
            VariableElement field = (VariableElement) enclosed;
            Column column = field.getAnnotation(Column.class);
            String targetTable = Optional.ofNullable(column).map(Column::table).filter(t -> !t.isEmpty())
                    .map(tableMappings::get)
                    .map(SecondaryTable::name)
                    .orElse(entity.getTableName());

            if (field.getAnnotation(ElementCollection.class) != null) {
                processElementCollection(field, entity);
            } else if (field.getAnnotation(Embedded.class) != null) {
                embeddedHandler.processEmbedded(field, entity.getColumns(), entity.getRelationships(), new HashSet<>());
            } else if (field.getAnnotation(EmbeddedId.class) == null) {
                ColumnModel columnModel = columnHandler.createFrom(field, Collections.emptyMap());
                if (columnModel != null) {
                    columnModel.setTableName(targetTable);
                    entity.getColumns().putIfAbsent(columnModel.getColumnName(), columnModel);
                }
            }
        }

        tableOpt.ifPresent(table -> {
            for (Index index : table.indexes()) {
                String indexName = index.name();
                if (entity.getIndexes().containsKey(indexName)) {
                    context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "Duplicate index name '" + indexName + "' in entity " + entityName, typeElement);
                    return;
                }
                IndexModel indexModel = IndexModel.builder()
                        .indexName(indexName)
                        .columnNames(Arrays.asList(index.columnList().split(",\\s*")))
                        .isUnique(index.unique())
                        .build();
                entity.getIndexes().put(indexModel.getIndexName(), indexModel);
            }
        });

        context.getSchemaModel().getEntities().putIfAbsent(entity.getEntityName(), entity);
    }

    private void processElementCollection(VariableElement field, EntityModel ownerEntity) {
        elementCollectionHandler.processElementCollection(field, ownerEntity);
    }

    private void processConstraints(Element element, String fieldName, List<ConstraintModel> constraints, String tableName) {
        constraintHandler.processConstraints(element, fieldName, constraints, tableName);
    }

    private List<VariableElement> getIdFields(TypeElement typeElement) {
        return typeElement.getEnclosedElements().stream()
                .filter(e -> e.getKind() == ElementKind.FIELD && e.getAnnotation(Id.class) != null)
                .map(VariableElement.class::cast)
                .collect(Collectors.toList());
    }

    private VariableElement getEmbeddedIdField(TypeElement typeElement) {
        return typeElement.getEnclosedElements().stream()
                .filter(e -> e.getKind() == ElementKind.FIELD && e.getAnnotation(EmbeddedId.class) != null)
                .map(VariableElement.class::cast)
                .findFirst().orElse(null);
    }

    private void processEmbeddedId(VariableElement embeddedIdField, EntityModel entity) {
        embeddedHandler.processEmbedded(embeddedIdField, entity.getColumns(), entity.getRelationships(), new HashSet<>());
        TypeElement embeddableType = (TypeElement) ((DeclaredType) embeddedIdField.asType()).asElement();
        for (Element enclosed : embeddableType.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.FIELD) {
                VariableElement field = (VariableElement) enclosed;
                String columnName = field.getSimpleName().toString();
                ColumnModel column = entity.getColumns().get(columnName);
                if (column != null) {
                    column.setPrimaryKey(true);
                }
            }
        }
    }

    private List<TypeElement> getMappedSuperclasses(TypeElement typeElement) {
        List<TypeElement> superclasses = new ArrayList<>();
        TypeMirror superclass = typeElement.getSuperclass();
        while (superclass != null && superclass.getKind() == TypeKind.DECLARED) {
            TypeElement superElement = (TypeElement) ((DeclaredType) superclass).asElement();
            if (superElement.getAnnotation(MappedSuperclass.class) != null) {
                superclasses.add(superElement);
            }
            superclass = superElement.getSuperclass();
        }
        Collections.reverse(superclasses);
        return superclasses;
    }

    private void processMappedSuperclass(TypeElement superclass, EntityModel entity) {
        for (Element enclosed : superclass.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.FIELD && enclosed.getAnnotation(Transient.class) == null) {
                VariableElement field = (VariableElement) enclosed;
                ColumnModel column = columnHandler.createFrom(field, Collections.emptyMap());
                if (column != null) {
                    entity.getColumns().putIfAbsent(column.getColumnName(), column);
                }
            }
        }
    }
}
