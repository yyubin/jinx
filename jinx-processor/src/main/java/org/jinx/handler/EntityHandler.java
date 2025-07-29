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

    public EntityHandler(ProcessingContext context, ColumnHandler columnHandler, EmbeddedHandler embeddedHandler, ConstraintHandler constraintHandler, SequenceHandler sequenceHandler) {
        this.context = context;
        this.columnHandler = columnHandler;
        this.embeddedHandler = embeddedHandler;
        this.constraintHandler = constraintHandler;
        this.sequenceHandler = sequenceHandler;
    }

    public void handle(TypeElement typeElement) {
        Table table = typeElement.getAnnotation(Table.class);
        String tableName = table != null && !table.name().isEmpty() ? table.name() : typeElement.getSimpleName().toString();
        String schema = table != null && !table.schema().isEmpty() ? table.schema() : null;
        String catalog = table != null && !table.catalog().isEmpty() ? table.catalog() : null;
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
            // Add FK relationship for secondary table
            Optional<String> pkColumnName = context.findPrimaryKeyColumnName(entity);
            if (pkColumnName.isPresent() && st.pkJoinColumns().length > 0) {
                String fkColumnName = st.pkJoinColumns()[0].name();
                RelationshipModel fkRelationship = RelationshipModel.builder()
                        .type(RelationshipType.SECONDARY_TABLE)
                        .column(fkColumnName)
                        .referencedTable(entity.getTableName())
                        .referencedColumn(pkColumnName.get())
                        .constraintName("fk_" + st.name() + "_" + fkColumnName)
                        .build();
                entity.getRelationships().add(fkRelationship);
            }
        }

        // Process mapped superclasses
        List<TypeElement> superclasses = getMappedSuperclasses(typeElement);
        for (TypeElement superclass : superclasses) {
            processMappedSuperclass(superclass, entity);
        }

        // Handle composite keys
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

        // Process fields (single loop)
        for (Element enclosed : typeElement.getEnclosedElements()) {
            if (enclosed.getAnnotation(Transient.class) != null ||
                    enclosed.getModifiers().contains(Modifier.TRANSIENT) ||
                    enclosed.getKind() != ElementKind.FIELD) {
                continue;
            }
            VariableElement field = (VariableElement) enclosed;
            Column column = field.getAnnotation(Column.class);
            String targetTable = entity.getTableName();
            if (column != null && !column.table().isEmpty()) {
                SecondaryTable tmpSecondaryTable = tableMappings.get(column.table());
                targetTable = tmpSecondaryTable != null ? tmpSecondaryTable.name() : entity.getTableName();
            }

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

        // Process indexes
        if (table != null) {
            for (Index index : table.indexes()) {
                String indexName = index.name();
                if (entity.getIndexes().containsKey(indexName)) {
                    context.getMessager().printMessage(Diagnostic.Kind.ERROR,
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

        context.getSchemaModel().getEntities().putIfAbsent(entity.getEntityName(), entity);
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
        // Mark embedded columns as primary keys
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

    private void processElementCollection(VariableElement field, EntityModel ownerEntity) {
        CollectionTable collectionTable = field.getAnnotation(CollectionTable.class);
        String tableName = collectionTable != null && !collectionTable.name().isEmpty()
                ? collectionTable.name()
                : ownerEntity.getTableName() + "_" + field.getSimpleName().toString();

        EntityModel collectionEntity = EntityModel.builder()
                .entityName(tableName)
                .tableName(tableName)
                .tableType(EntityModel.TableType.COLLECTION_TABLE)
                .build();

        // Foreign key to owner entity
        Optional<String> ownerPkOpt = context.findPrimaryKeyColumnName(ownerEntity);
        if (ownerPkOpt.isEmpty()) {
            context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Owner entity " + ownerEntity.getEntityName() + " must have a primary key for ElementCollection", field);
            return;
        }
        String fkColumnName = ownerEntity.getTableName() + "_id";
        String ownerPkType = ownerEntity.getColumns().get(ownerPkOpt.get()).getJavaType();
        ColumnModel fkColumn = ColumnModel.builder()
                .columnName(fkColumnName)
                .javaType(ownerPkType)
                .isPrimaryKey(true)
                .build();
        collectionEntity.getColumns().put(fkColumnName, fkColumn);

        // Check if it's a Map
        TypeMirror fieldType = field.asType();
        boolean isMap = fieldType instanceof DeclaredType &&
                context.getTypeUtils().isSubtype(fieldType, context.getElementUtils().getTypeElement("java.util.Map").asType());
        TypeMirror keyType = null, valueType = null;
        if (isMap) {
            DeclaredType mapType = (DeclaredType) fieldType;
            keyType = mapType.getTypeArguments().get(0);
            valueType = mapType.getTypeArguments().get(1);
        } else {
            valueType = ((DeclaredType) fieldType).getTypeArguments().get(0);
        }

        // Handle Map key column
        if (isMap) {
            MapKey mapKey = field.getAnnotation(MapKey.class);
            MapKeyColumn mapKeyColumn = field.getAnnotation(MapKeyColumn.class);
            MapKeyEnumerated mapKeyEnumerated = field.getAnnotation(MapKeyEnumerated.class);
            MapKeyTemporal mapKeyTemporal = field.getAnnotation(MapKeyTemporal.class);

            String mapKeyColumnName = mapKeyColumn != null && !mapKeyColumn.name().isEmpty() ? mapKeyColumn.name() : "map_key";
            ColumnModel keyColumn = ColumnModel.builder()
                    .columnName(mapKeyColumnName)
                    .javaType(keyType.toString())
                    .isPrimaryKey(true)
                    .isMapKey(true)
                    .build();

            if (mapKey != null) {
                keyColumn.setMapKeyType("entity:" + mapKey.name());
            }
            if (mapKeyEnumerated != null) {
                keyColumn.setEnumStringMapping(mapKeyEnumerated.value() == EnumType.STRING);
                if (keyColumn.isEnumStringMapping()) {
                    keyColumn.setMapKeyEnumValues(getEnumConstants(keyType));
                }
            }
            if (mapKeyTemporal != null) {
                keyColumn.setMapKeyTemporalType(mapKeyTemporal.value());
            }

            collectionEntity.getColumns().put(mapKeyColumnName, keyColumn);
        }

        // Element column
        String elementColumnName = field.getSimpleName().toString();
        if (valueType instanceof DeclaredType && ((DeclaredType) valueType).asElement().getAnnotation(Embeddable.class) != null) {
            embeddedHandler.processEmbedded(field, collectionEntity.getColumns(), collectionEntity.getRelationships(), new HashSet<>());
        } else {
            ColumnModel elementColumn = ColumnModel.builder()
                    .columnName(elementColumnName)
                    .javaType(valueType.toString())
                    .isPrimaryKey(true)
                    .build();
            if (((DeclaredType) valueType).asElement().getKind() == ElementKind.ENUM) {
                elementColumn.setEnumStringMapping(field.getAnnotation(Enumerated.class) != null &&
                        field.getAnnotation(Enumerated.class).value() == EnumType.STRING);
                if (elementColumn.isEnumStringMapping()) {
                    elementColumn.setEnumValues(getEnumConstants(valueType));
                }
            }
            collectionEntity.getColumns().put(elementColumnName, elementColumn);
        }

        // Handle @OrderColumn
        OrderColumn orderColumn = field.getAnnotation(OrderColumn.class);
        if (orderColumn != null && !isMap) {
            String orderColumnName = orderColumn.name().isEmpty() ? "order_idx" : orderColumn.name();
            ColumnModel orderCol = ColumnModel.builder()
                    .columnName(orderColumnName)
                    .javaType("int")
                    .isPrimaryKey(true)
                    .build();
            collectionEntity.getColumns().put(orderColumnName, orderCol);
        }

        // Add FK constraint
        RelationshipModel fkRelationship = RelationshipModel.builder()
                .type(RelationshipType.ELEMENT_COLLECTION)
                .column(fkColumnName)
                .referencedTable(ownerEntity.getTableName())
                .referencedColumn(ownerPkOpt.get())
                .constraintName("fk_" + tableName + "_" + fkColumnName)
                .build();
        collectionEntity.getRelationships().add(fkRelationship);

        context.getSchemaModel().getEntities().putIfAbsent(collectionEntity.getEntityName(), collectionEntity);
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