package org.jinx.handler;

import jakarta.persistence.*;
import org.jinx.context.ProcessingContext;
import org.jinx.model.ColumnModel;
import org.jinx.model.EntityModel;
import org.jinx.model.SchemaModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import javax.annotation.processing.Messager;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class EntityHandlerTest {

    @Mock
    private ProcessingContext context;
    @Mock
    private ColumnHandler columnHandler;
    @Mock
    private EmbeddedHandler embeddedHandler;
    @Mock
    private ConstraintHandler constraintHandler;
    @Mock
    private SequenceHandler sequenceHandler;
    @Mock
    private SchemaModel schemaModel;
    @Mock
    private Messager messager;
    @Mock
    private ElementCollectionHandler elementCollectionHandler;

    // Main @Entity class element
    @Mock
    private TypeElement entityTypeElement;
    @Mock
    private Name entityName;

    // Fields within the entity
    @Mock
    private VariableElement idField;
    @Mock
    private Name idFieldName;
    @Mock
    private VariableElement nameField;
    @Mock
    private Name nameFieldName;
    @Mock
    private VariableElement transientField;
    @Mock
    private VariableElement embeddedField;

    private EntityHandler entityHandler;
    private Map<String, EntityModel> entitiesMap;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        entitiesMap = spy(new HashMap<>());

        // Setup main handler and its dependencies
        entityHandler = new EntityHandler(context, columnHandler, embeddedHandler, constraintHandler, sequenceHandler, elementCollectionHandler);

        // Common context setup
        Types typeUtils   = mock(Types.class);
        Elements elemUtils = mock(Elements.class);
        when(context.getTypeUtils()).thenReturn(typeUtils);
        when(context.getElementUtils()).thenReturn(elemUtils);
        when(elemUtils.getTypeElement("java.util.Map")).thenReturn(mock(TypeElement.class));
        when(context.getSchemaModel()).thenReturn(schemaModel);
        when(schemaModel.getEntities()).thenReturn(entitiesMap);
        when(context.getMessager()).thenReturn(messager);

        // Setup for the main @Entity class
        when(entityTypeElement.getQualifiedName()).thenReturn(entityName);
        when(entityName.toString()).thenReturn("com.example.User");
        when(entityTypeElement.getSimpleName()).thenReturn(entityName);
        when(entityTypeElement.getSuperclass()).thenReturn(mock(DeclaredType.class)); // Default to avoid NPE

        // Setup for fields
        when(idField.getKind()).thenReturn(javax.lang.model.element.ElementKind.FIELD);
        when(idField.getSimpleName()).thenReturn(idFieldName);
        when(idFieldName.toString()).thenReturn("id");
        when(idField.getAnnotation(Id.class)).thenReturn(mock(Id.class));

        when(nameField.getKind()).thenReturn(javax.lang.model.element.ElementKind.FIELD);
        when(nameField.getSimpleName()).thenReturn(nameFieldName);
        when(nameFieldName.toString()).thenReturn("name");

        when(transientField.getKind()).thenReturn(javax.lang.model.element.ElementKind.FIELD);
        when(transientField.getAnnotation(Transient.class)).thenReturn(mock(Transient.class));

        when(embeddedField.getKind()).thenReturn(javax.lang.model.element.ElementKind.FIELD);
        when(embeddedField.getAnnotation(Embedded.class)).thenReturn(mock(Embedded.class));
    }

    @Test
    @DisplayName("Should process a simple entity and its basic fields")
    void handle_WithSimpleEntity_CreatesEntityModelAndColumns() {
        // Given
        Name qName = mock(Name.class);
        when(qName.toString()).thenReturn("com.example.User");
        when(entityTypeElement.getQualifiedName()).thenReturn(qName);

        Name sName = mock(Name.class);
        when(sName.toString()).thenReturn("User");
        when(entityTypeElement.getSimpleName()).thenReturn(sName);

        doReturn(List.of(idField, nameField, transientField)).when(entityTypeElement).getEnclosedElements();

        when(columnHandler.createFrom(eq(idField), any())).thenReturn(ColumnModel.builder().columnName("id").isPrimaryKey(true).build());
        when(columnHandler.createFrom(eq(nameField), any())).thenReturn(ColumnModel.builder().columnName("name").build());

        // When
        entityHandler.handle(entityTypeElement);

        // Then
        assertThat(entitiesMap).containsKey("com.example.User");
        EntityModel createdEntity = entitiesMap.get("com.example.User");
        assertThat(createdEntity.getTableName()).isEqualTo("User");
        assertThat(createdEntity.getColumns()).hasSize(2);
        assertThat(createdEntity.getColumns()).containsKey("id");
        assertThat(createdEntity.getColumns()).containsKey("name");

        // Verify transient field was skipped and not passed to columnHandler
        verify(columnHandler, never()).createFrom(eq(transientField), any());
    }

    @Test
    @DisplayName("Should correctly handle @Table annotation")
    void handle_WithTableAnnotation_SetsTableDetails() {
        // Given
        Table table = mock(Table.class);
        when(table.name()).thenReturn("custom_users");
        when(table.schema()).thenReturn("public");
        when(table.catalog()).thenReturn("main_db");
        when(table.indexes()).thenReturn(new Index[0]);
        when(entityTypeElement.getAnnotation(Table.class)).thenReturn(table);
        when(entityTypeElement.getEnclosedElements()).thenReturn(Collections.emptyList());

        // When
        entityHandler.handle(entityTypeElement);

        // Then
        EntityModel createdEntity = entitiesMap.get("com.example.User");
        assertThat(createdEntity.getTableName()).isEqualTo("custom_users");
        assertThat(createdEntity.getSchema()).isEqualTo("public");
        assertThat(createdEntity.getCatalog()).isEqualTo("main_db");
    }

    @Test
    @DisplayName("Should call EmbeddedHandler for @Embedded fields")
    void handle_WithEmbeddedField_CallsEmbeddedHandler() {
        // Given
        doReturn(List.of(embeddedField)).when(entityTypeElement).getEnclosedElements();

        // When
        entityHandler.handle(entityTypeElement);

        // Then
        verify(embeddedHandler).processEmbedded(eq(embeddedField), any(), any(), any());
    }

    @Test
    @DisplayName("Should process @IdClass and mark multiple fields as primary keys")
    void handle_WithIdClass_MarksMultiplePrimaryKeys() {
        // Given
        when(entityTypeElement.getAnnotation(IdClass.class)).thenReturn(mock(IdClass.class));
        // Simulate two @Id fields for the composite key
        VariableElement idField2 = mock(VariableElement.class);
        Name idFieldName2 = mock(Name.class);
        when(idField2.getKind()).thenReturn(javax.lang.model.element.ElementKind.FIELD);
        when(idField2.getAnnotation(Id.class)).thenReturn(mock(Id.class));
        when(idField2.getSimpleName()).thenReturn(idFieldName2);
        when(idFieldName2.toString()).thenReturn("departmentId");

        doReturn(List.of(idField, idField2)).when(entityTypeElement).getEnclosedElements();

        when(columnHandler.createFrom(eq(idField), any())).thenReturn(ColumnModel.builder().columnName("id").build());
        when(columnHandler.createFrom(eq(idField2), any())).thenReturn(ColumnModel.builder().columnName("departmentId").build());

        // When
        entityHandler.handle(entityTypeElement);

        // Then
        EntityModel createdEntity = entitiesMap.get("com.example.User");
        assertThat(createdEntity.getColumns()).hasSize(2);
        assertThat(createdEntity.getColumns().get("id").isPrimaryKey()).isTrue();
        assertThat(createdEntity.getColumns().get("departmentId").isPrimaryKey()).isTrue();
    }

    @Test
    @DisplayName("@ElementCollection 필드가 있으면 ElementCollectionHandler에게 처리를 위임해야 한다")
    void handle_shouldDelegateToElementCollectionHandler_whenFieldIsElementCollection() {
        // Arrange
        Name entityName = mock(Name.class);
        when(entityName.toString()).thenReturn("com.example.User");
        when(entityTypeElement.getQualifiedName()).thenReturn(entityName);
        when(entityTypeElement.getSimpleName()).thenReturn(mock(Name.class));
        when(entityTypeElement.getSimpleName().toString()).thenReturn("User");

        VariableElement collectionField = mock(VariableElement.class);
        when(collectionField.getKind()).thenReturn(ElementKind.FIELD);
        when(collectionField.getAnnotation(ElementCollection.class)).thenReturn(mock(ElementCollection.class));
        doReturn(List.of(collectionField)).when(entityTypeElement).getEnclosedElements();

        // Act
        entityHandler.handle(entityTypeElement);

        // Then
        // ElementCollectionHandler의 processElementCollection 메서드가 호출되었는지 검증
        ArgumentCaptor<EntityModel> ownerEntityCaptor = ArgumentCaptor.forClass(EntityModel.class);
        verify(elementCollectionHandler).processElementCollection(eq(collectionField), ownerEntityCaptor.capture());

        // 캡처된 엔티티가 예상하는 "User" 엔티티인지 확인
        EntityModel capturedOwner = ownerEntityCaptor.getValue();
        assertThat(capturedOwner.getTableName()).isEqualTo("User");
    }

    @Test
    @DisplayName("Should process @MappedSuperclass and inherit its fields")
    void handle_WithMappedSuperclass_InheritsColumns() {
        // Given
        TypeElement superclassElement = mock(TypeElement.class);
        DeclaredType superclassType = mock(DeclaredType.class);
        VariableElement superclassField = mock(VariableElement.class);
        Name superclassFieldName = mock(Name.class);

        // Link entity to its superclass
        when(entityTypeElement.getSuperclass()).thenReturn(superclassType);
        when(superclassType.asElement()).thenReturn(superclassElement);
        when(superclassType.getKind()).thenReturn(TypeKind.DECLARED);

        // Configure the superclass
        when(superclassElement.getAnnotation(MappedSuperclass.class)).thenReturn(mock(MappedSuperclass.class));
        doReturn(List.of(superclassField)).when(superclassElement).getEnclosedElements();
        when(superclassElement.getSuperclass()).thenReturn(mock(DeclaredType.class)); // End of hierarchy

        // Configure the field within the superclass
        when(superclassField.getKind()).thenReturn(javax.lang.model.element.ElementKind.FIELD);
        when(superclassField.getSimpleName()).thenReturn(superclassFieldName);
        when(superclassFieldName.toString()).thenReturn("createdDate");

        when(columnHandler.createFrom(eq(superclassField), any())).thenReturn(ColumnModel.builder().columnName("createdDate").build());
        when(entityTypeElement.getEnclosedElements()).thenReturn(Collections.emptyList());

        // When
        entityHandler.handle(entityTypeElement);

        // Then
        EntityModel createdEntity = entitiesMap.get("com.example.User");
        assertThat(createdEntity.getColumns()).hasSize(1);
        assertThat(createdEntity.getColumns()).containsKey("createdDate");
    }

    @Test
    @DisplayName("Should skip duplicate entity and log an error")
    void handle_WithDuplicateEntity_LogsErrorAndSkips() {
        // Given
        EntityModel existing = EntityModel.builder().entityName("com.example.User").isValid(true).build();
        entitiesMap.put("com.example.User", existing);

        // When
        entityHandler.handle(entityTypeElement);

        // Then
        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR), contains("Duplicate entity found"), eq(entityTypeElement));
        EntityModel stored = entitiesMap.get("com.example.User");
        assertThat(stored.isValid()).isTrue();
    }

    @Test
    @DisplayName("Should skip fields with modifier transient")
    void handle_WithModifierTransientField_SkipsField() {
        // Given
        Set<Modifier> modifiers = new HashSet<>();
        modifiers.add(Modifier.TRANSIENT);
        when(nameField.getModifiers()).thenReturn(modifiers);

        doReturn(List.of(nameField)).when(entityTypeElement).getEnclosedElements();

        // When
        entityHandler.handle(entityTypeElement);

        // Then
        verify(columnHandler, never()).createFrom(eq(nameField), any());
    }

    @Test
    @DisplayName("Should skip field if @Column's table is invalid")
    void handle_WithInvalidSecondaryTable_FallbacksToPrimaryTable() {
        // Given
        Column columnAnnotation = mock(Column.class);
        when(columnAnnotation.table()).thenReturn("non_existent_table");
        when(nameField.getAnnotation(Column.class)).thenReturn(columnAnnotation);
        when(nameField.getKind()).thenReturn(javax.lang.model.element.ElementKind.FIELD);
        when(nameField.getSimpleName()).thenReturn(mock(Name.class));
        when(nameField.getAnnotation(Transient.class)).thenReturn(null);

        ColumnModel model = ColumnModel.builder().columnName("name").build();
        when(columnHandler.createFrom(eq(nameField), any())).thenReturn(model);
        doReturn(List.of(nameField)).when(entityTypeElement).getEnclosedElements();

        // When
        entityHandler.handle(entityTypeElement);

        // Then
        EntityModel created = entitiesMap.get("com.example.User");
        assertThat(created.getColumns()).containsKey("name");
    }

    @Test
    @DisplayName("Should process Index annotations on table")
    void handle_WithIndexes_AddsIndexModels() {
        // Given
        Index index1 = mock(Index.class);
        when(index1.name()).thenReturn("idx_username");
        when(index1.columnList()).thenReturn("username");
        when(index1.unique()).thenReturn(true);

        Table table = mock(Table.class);
        when(table.name()).thenReturn("users");
        when(table.indexes()).thenReturn(new Index[]{index1});
        when(table.schema()).thenReturn("public");
        when(table.catalog()).thenReturn("main_db");
        when(entityTypeElement.getAnnotation(Table.class)).thenReturn(table);
        Name name = mock(Name.class);
        when(name.toString()).thenReturn("User");
        when(entityTypeElement.getSimpleName()).thenReturn(name);
        when(entityTypeElement.getEnclosedElements()).thenReturn(Collections.emptyList());

        // When
        entityHandler.handle(entityTypeElement);

        // Then
        EntityModel model = entitiesMap.get("com.example.User");
        assertThat(model.getIndexes()).containsKey("idx_username");
        assertThat(model.getIndexes().get("idx_username").isUnique()).isTrue();
    }

}
