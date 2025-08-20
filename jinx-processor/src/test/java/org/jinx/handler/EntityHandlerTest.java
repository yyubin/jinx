package org.jinx.handler;

import jakarta.persistence.*;
import org.jinx.context.ProcessingContext;
import org.jinx.context.DefaultNaming;
import org.jinx.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EntityHandlerTest {

    @Mock private ProcessingContext context;
    @Mock private ColumnHandler columnHandler;
    @Mock private EmbeddedHandler embeddedHandler;
    @Mock private ConstraintHandler constraintHandler;
    @Mock private SequenceHandler sequenceHandler;
    @Mock private ElementCollectionHandler elementCollectionHandler;
    @Mock private TableGeneratorHandler tableGeneratorHandler;
    @Mock private TypeElement typeElement;
    @Mock private javax.annotation.processing.Messager messager;

    private EntityHandler entityHandler;
    private SchemaModel schemaModel;
    private DefaultNaming naming;

    @BeforeEach
    void setUp() {
        schemaModel = new SchemaModel();
        naming = new DefaultNaming(63);

        when(context.getSchemaModel()).thenReturn(schemaModel);
        when(context.getNaming()).thenReturn(naming);
        when(context.getMessager()).thenReturn(messager);

        entityHandler = new EntityHandler(context, columnHandler, embeddedHandler,
                constraintHandler, sequenceHandler, elementCollectionHandler, tableGeneratorHandler);
    }

    @Test
    void testCreateEntityModel_WithTableAnnotation() {
        // Given
        Table tableAnnotation = mock(Table.class);
        when(tableAnnotation.name()).thenReturn("custom_table");
        when(typeElement.getAnnotation(Table.class)).thenReturn(tableAnnotation);
        when(typeElement.getQualifiedName()).thenReturn(mockName("com.example.TestEntity"));
        when(typeElement.getSimpleName()).thenReturn(mockName("TestEntity"));

        // When
        entityHandler.handle(typeElement);

        // Then
        EntityModel entity = schemaModel.getEntities().get("com.example.TestEntity");
        assertNotNull(entity);
        assertEquals("com.example.TestEntity", entity.getEntityName());
        assertEquals("custom_table", entity.getTableName());
        assertTrue(entity.isValid());
    }

    @Test
    void testCreateEntityModel_WithoutTableAnnotation() {
        // Given
        when(typeElement.getAnnotation(Table.class)).thenReturn(null);
        when(typeElement.getQualifiedName()).thenReturn(mockName("com.example.TestEntity"));
        when(typeElement.getSimpleName()).thenReturn(mockName("TestEntity"));
        when(typeElement.getEnclosedElements()).thenReturn(Collections.emptyList());
        when(typeElement.getSuperclass()).thenReturn(mockTypeMirror(TypeKind.NONE));

        // When
        entityHandler.handle(typeElement);

        // Then
        EntityModel entity = schemaModel.getEntities().get("com.example.TestEntity");
        assertNotNull(entity);
        assertEquals("com.example.TestEntity", entity.getEntityName());
        assertEquals("TestEntity", entity.getTableName()); // Uses simple name as default
        assertTrue(entity.isValid());
    }

    @Test
    void testCreateEntityModel_DuplicateEntity() {
        // Given
        String entityName = "com.example.TestEntity";
        when(typeElement.getQualifiedName()).thenReturn(mockName(entityName));

        // Pre-populate with existing entity
        EntityModel existingEntity = EntityModel.builder()
                .entityName(entityName)
                .tableName("existing_table")
                .isValid(true)
                .build();
        schemaModel.getEntities().put(entityName, existingEntity);

        // When
        entityHandler.handle(typeElement);

        // Then
        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR),
                contains("Duplicate entity found"), eq(typeElement));

        // Original entity should remain unchanged
        EntityModel entity = schemaModel.getEntities().get(entityName);
        assertEquals("existing_table", entity.getTableName());
        assertTrue(entity.isValid());
    }

    @Test
    void testProcessMappedSuperclass() {
        // Given
        TypeElement superclassElement = mock(TypeElement.class);
        VariableElement field1 = mockField("field1", null);
        VariableElement field2 = mockField("field2", null);

        when(superclassElement.getAnnotation(MappedSuperclass.class)).thenReturn(mock(MappedSuperclass.class));
        doReturn(Arrays.asList(field1, field2)).when(superclassElement).getEnclosedElements();
        when(superclassElement.getSuperclass()).thenReturn(mockTypeMirror(TypeKind.NONE));

        TypeMirror superTypeMirror = mock(DeclaredType.class);
        when(superTypeMirror.getKind()).thenReturn(TypeKind.DECLARED);
        when(((DeclaredType) superTypeMirror).asElement()).thenReturn(superclassElement);

        when(typeElement.getSuperclass()).thenReturn(superTypeMirror);
        when(typeElement.getQualifiedName()).thenReturn(mockName("com.example.TestEntity"));
        when(typeElement.getSimpleName()).thenReturn(mockName("TestEntity"));
        when(typeElement.getAnnotation(Table.class)).thenReturn(null);
        when(typeElement.getEnclosedElements()).thenReturn(Collections.emptyList());

        ColumnModel column1 = ColumnModel.builder().columnName("field1").build();
        ColumnModel column2 = ColumnModel.builder().columnName("field2").build();
        when(columnHandler.createFrom(eq(field1), any())).thenReturn(column1);
        when(columnHandler.createFrom(eq(field2), any())).thenReturn(column2);

        // When
        entityHandler.handle(typeElement);

        // Then
        EntityModel entity = schemaModel.getEntities().get("com.example.TestEntity");
        assertNotNull(entity);
        assertEquals(2, entity.getColumns().size());
        assertTrue(entity.getColumns().containsKey("field1"));
        assertTrue(entity.getColumns().containsKey("field2"));
    }

    @Test
    void testProcessEmbeddedId() {
        // Given
        VariableElement embeddedIdField = mockField("compositeId", EmbeddedId.class);
        TypeElement embeddableType = mock(TypeElement.class);
        VariableElement keyField1 = mockField("key1", null);
        VariableElement keyField2 = mockField("key2", null);

        DeclaredType embeddedIdType = mock(DeclaredType.class);
        when(embeddedIdField.asType()).thenReturn(embeddedIdType);
        when(embeddedIdType.asElement()).thenReturn(embeddableType);
        doReturn(Arrays.asList(keyField1, keyField2)).when(embeddableType).getEnclosedElements();
        when(typeElement.getQualifiedName()).thenReturn(mockName("com.example.TestEntity"));
        when(typeElement.getSimpleName()).thenReturn(mockName("TestEntity"));
        when(typeElement.getAnnotation(Table.class)).thenReturn(null);
        doReturn(Arrays.asList(embeddedIdField)).when(typeElement).getEnclosedElements();
        when(typeElement.getSuperclass()).thenReturn(mockTypeMirror(TypeKind.NONE));

        // When
        entityHandler.handle(typeElement);

        // Then
        verify(embeddedHandler).processEmbedded(eq(embeddedIdField), any(EntityModel.class), any(HashSet.class));
    }

    @Test
    void testProcessSecondaryTable() {
        // Given
        SecondaryTable secondaryTable = mock(SecondaryTable.class);
        when(secondaryTable.name()).thenReturn("secondary_table");
        when(secondaryTable.pkJoinColumns()).thenReturn(new PrimaryKeyJoinColumn[0]);

        when(typeElement.getAnnotation(SecondaryTable.class)).thenReturn(secondaryTable);
        when(typeElement.getAnnotation(SecondaryTables.class)).thenReturn(null);
        when(typeElement.getQualifiedName()).thenReturn(mockName("com.example.TestEntity"));
        when(typeElement.getSimpleName()).thenReturn(mockName("TestEntity"));
        when(typeElement.getAnnotation(Table.class)).thenReturn(null);
        when(typeElement.getEnclosedElements()).thenReturn(Collections.emptyList());
        when(typeElement.getSuperclass()).thenReturn(mockTypeMirror(TypeKind.NONE));

        // Mock primary key columns for join processing
        ColumnModel pkColumn = ColumnModel.builder()
                .columnName("id")
                .isPrimaryKey(true)
                .build();
        when(context.findAllPrimaryKeyColumns(any(EntityModel.class)))
                .thenReturn(Arrays.asList(pkColumn));

        // When
        entityHandler.handle(typeElement);

        // Then
        EntityModel entity = schemaModel.getEntities().get("com.example.TestEntity");
        assertNotNull(entity);
        assertTrue(entity.getRelationships().size() > 0);
    }

    @Test
    void testProcessInheritanceJoin() {
        // Given
        TypeElement parentType = mock(TypeElement.class);
        Inheritance inheritance = mock(Inheritance.class);
        when(inheritance.strategy()).thenReturn(InheritanceType.JOINED);
        when(parentType.getAnnotation(Inheritance.class)).thenReturn(inheritance);
        when(parentType.getQualifiedName()).thenReturn(mockName("com.example.ParentEntity"));

        DeclaredType parentTypeMirror = mock(DeclaredType.class);
        when(parentTypeMirror.getKind()).thenReturn(TypeKind.DECLARED);
        when(parentTypeMirror.asElement()).thenReturn(parentType);

        when(typeElement.getSuperclass()).thenReturn(parentTypeMirror);
        when(typeElement.getQualifiedName()).thenReturn(mockName("com.example.ChildEntity"));
        when(typeElement.getSimpleName()).thenReturn(mockName("ChildEntity"));
        when(typeElement.getAnnotation(Table.class)).thenReturn(null);
        when(typeElement.getAnnotation(PrimaryKeyJoinColumns.class)).thenReturn(null);
        when(typeElement.getAnnotation(PrimaryKeyJoinColumn.class)).thenReturn(null);
        when(typeElement.getEnclosedElements()).thenReturn(Collections.emptyList());

        // Setup parent entity in schema
        EntityModel parentEntity = EntityModel.builder()
                .entityName("com.example.ParentEntity")
                .tableName("parent_table")
                .isValid(true)
                .build();
        schemaModel.getEntities().put("com.example.ParentEntity", parentEntity);

        ColumnModel parentPkColumn = ColumnModel.builder()
                .columnName("id")
                .isPrimaryKey(true)
                .build();
        when(context.findAllPrimaryKeyColumns(parentEntity))
                .thenReturn(Arrays.asList(parentPkColumn));

        // When
        entityHandler.handle(typeElement);

        // Then
        EntityModel childEntity = schemaModel.getEntities().get("com.example.ChildEntity");
        assertNotNull(childEntity);
        assertTrue(childEntity.getRelationships().size() > 0);

        // Verify FK relationship was created
        RelationshipModel relationship = childEntity.getRelationships().values().iterator().next();
        assertEquals(RelationshipType.SECONDARY_TABLE, relationship.getType());
        assertEquals("parent_table", relationship.getReferencedTable());
    }

    @Test
    void testProcessRegularFields() {
        // Given
        VariableElement regularField = mockField("regularField", null);
        when(regularField.getAnnotation(ElementCollection.class)).thenReturn(null);
        when(regularField.getAnnotation(Embedded.class)).thenReturn(null);
        when(regularField.getAnnotation(EmbeddedId.class)).thenReturn(null);

        when(typeElement.getQualifiedName()).thenReturn(mockName("com.example.TestEntity"));
        when(typeElement.getSimpleName()).thenReturn(mockName("TestEntity"));
        when(typeElement.getAnnotation(Table.class)).thenReturn(null);
        doReturn(Arrays.asList(regularField)).when(typeElement).getEnclosedElements();
        when(typeElement.getSuperclass()).thenReturn(mockTypeMirror(TypeKind.NONE));

        ColumnModel columnModel = ColumnModel.builder()
                .columnName("regular_field")
                .build();
        when(columnHandler.createFrom(eq(regularField), any())).thenReturn(columnModel);

        // When
        entityHandler.handle(typeElement);

        // Then
        EntityModel entity = schemaModel.getEntities().get("com.example.TestEntity");
        assertNotNull(entity);
        assertEquals(1, entity.getColumns().size());
        assertTrue(entity.getColumns().containsKey("regular_field"));
    }

    @Test
    void testProcessElementCollection() {
        // Given
        VariableElement collectionField = mockField("collectionField", ElementCollection.class);

        when(typeElement.getQualifiedName()).thenReturn(mockName("com.example.TestEntity"));
        when(typeElement.getSimpleName()).thenReturn(mockName("TestEntity"));
        when(typeElement.getAnnotation(Table.class)).thenReturn(null);
        doReturn(Arrays.asList(collectionField)).when(typeElement).getEnclosedElements();
        when(typeElement.getSuperclass()).thenReturn(mockTypeMirror(TypeKind.NONE));

        // When
        entityHandler.handle(typeElement);

        // Then
        verify(elementCollectionHandler).processElementCollection(eq(collectionField), any(EntityModel.class));
    }

    @Test
    void testSkipTransientFields() {
        // Given
        VariableElement transientField = mockField("transientField", Transient.class);

        when(typeElement.getQualifiedName()).thenReturn(mockName("com.example.TestEntity"));
        when(typeElement.getSimpleName()).thenReturn(mockName("TestEntity"));
        when(typeElement.getAnnotation(Table.class)).thenReturn(null);
        doReturn(Arrays.asList(transientField)).when(typeElement).getEnclosedElements();
        when(typeElement.getSuperclass()).thenReturn(mockTypeMirror(TypeKind.NONE));

        // When
        entityHandler.handle(typeElement);

        // Then
        EntityModel entity = schemaModel.getEntities().get("com.example.TestEntity");
        assertNotNull(entity);
        assertEquals(0, entity.getColumns().size()); // Transient field should be skipped

        verify(columnHandler, never()).createFrom(eq(transientField), any());
    }

    @Test
    void testSkipStaticFields() {
        // Given
        VariableElement staticField = mock(VariableElement.class);
        when(staticField.getKind()).thenReturn(ElementKind.FIELD);
        when(staticField.getModifiers()).thenReturn(EnumSet.of(Modifier.STATIC));
        when(staticField.getAnnotation(Transient.class)).thenReturn(null);
        when(staticField.getSimpleName()).thenReturn(mockName("staticField"));

        when(typeElement.getQualifiedName()).thenReturn(mockName("com.example.TestEntity"));
        when(typeElement.getSimpleName()).thenReturn(mockName("TestEntity"));
        when(typeElement.getAnnotation(Table.class)).thenReturn(null);
        doReturn(Arrays.asList(staticField)).when(typeElement).getEnclosedElements();
        when(typeElement.getSuperclass()).thenReturn(mockTypeMirror(TypeKind.NONE));

        // When
        entityHandler.handle(typeElement);

        // Then
        EntityModel entity = schemaModel.getEntities().get("com.example.TestEntity");
        assertNotNull(entity);
        assertEquals(0, entity.getColumns().size()); // Static field should be skipped

        verify(columnHandler, never()).createFrom(eq(staticField), any());
    }

    @Test
    void testIdClassNotSupported() {
        // Given
        IdClass idClass = mock(IdClass.class);
        when(typeElement.getAnnotation(IdClass.class)).thenReturn(idClass);
        when(typeElement.getQualifiedName()).thenReturn(mockName("com.example.TestEntity"));
        when(typeElement.getSimpleName()).thenReturn(mockName("TestEntity"));
        when(typeElement.getAnnotation(Table.class)).thenReturn(null);
        when(typeElement.getEnclosedElements()).thenReturn(Collections.emptyList());
        when(typeElement.getSuperclass()).thenReturn(mockTypeMirror(TypeKind.NONE));

        // When
        entityHandler.handle(typeElement);

        // Then
        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR),
                contains("IdClass is not supported"), eq(typeElement));
    }

    // Helper methods
    private VariableElement mockField(String name, Class<? extends java.lang.annotation.Annotation> annotationClass) {
        VariableElement field = mock(VariableElement.class);
        when(field.getKind()).thenReturn(ElementKind.FIELD);
        when(field.getModifiers()).thenReturn(EnumSet.noneOf(Modifier.class));
        when(field.getSimpleName()).thenReturn(mockName(name));

        if (annotationClass != null) {
            Object annotation = mock(annotationClass);
            doReturn(annotation).when(field).getAnnotation(annotationClass);
        }

        // Set all other annotation getters to return null
        when(field.getAnnotation(Transient.class)).thenReturn(
                annotationClass == Transient.class ? (Transient) field.getAnnotation(annotationClass) : null);
        when(field.getAnnotation(ElementCollection.class)).thenReturn(
                annotationClass == ElementCollection.class ? (ElementCollection) field.getAnnotation(annotationClass) : null);
        when(field.getAnnotation(Embedded.class)).thenReturn(
                annotationClass == Embedded.class ? (Embedded) field.getAnnotation(annotationClass) : null);
        when(field.getAnnotation(EmbeddedId.class)).thenReturn(
                annotationClass == EmbeddedId.class ? (EmbeddedId) field.getAnnotation(annotationClass) : null);
        when(field.getAnnotation(Column.class)).thenReturn(
                annotationClass == Column.class ? (Column) field.getAnnotation(annotationClass) : null);

        return field;
    }

    private Name mockName(String name) {
        Name mockName = mock(Name.class);
        when(mockName.toString()).thenReturn(name);
        return mockName;
    }

    private TypeMirror mockTypeMirror(TypeKind kind) {
        TypeMirror typeMirror = mock(TypeMirror.class);
        when(typeMirror.getKind()).thenReturn(kind);
        return typeMirror;
    }
}