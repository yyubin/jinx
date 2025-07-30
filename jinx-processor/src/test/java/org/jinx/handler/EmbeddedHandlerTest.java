package org.jinx.handler;

import jakarta.persistence.*;
import org.jinx.context.ProcessingContext;
import org.jinx.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import javax.lang.model.element.Element;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class EmbeddedHandlerTest {

    @Mock
    private ProcessingContext context;
    @Mock
    private ColumnHandler columnHandler;
    @Mock
    private SchemaModel schemaModel;

    // Main field annotated with @Embedded
    @Mock
    private VariableElement embeddedField;
    @Mock
    private Name embeddedFieldName;

    // The @Embeddable class element
    @Mock
    private TypeElement embeddableTypeElement;
    @Mock
    private Name embeddableTypeName;
    @Mock
    private DeclaredType embeddableDeclaredType;

    // Fields inside the @Embeddable class
    @Mock
    private VariableElement simpleFieldInEmbeddable;
    @Mock
    private Name simpleFieldName;
    @Mock
    private VariableElement relationshipFieldInEmbeddable;
    @Mock
    private Name relationshipFieldName;

    private EmbeddedHandler embeddedHandler;
    private Map<String, ColumnModel> columns;
    private List<RelationshipModel> relationships;
    private Set<String> processedTypes;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        embeddedHandler = new EmbeddedHandler(context, columnHandler);

        // Initialize collections for each test
        columns = new HashMap<>();
        relationships = new ArrayList<>();
        processedTypes = new HashSet<>();

        // Common mock setup
        when(context.getSchemaModel()).thenReturn(schemaModel);

        // Setup for the main @Embedded field
        when(embeddedField.asType()).thenReturn(embeddableDeclaredType);
        when(embeddedField.getSimpleName()).thenReturn(embeddedFieldName);
        when(embeddedFieldName.toString()).thenReturn("address");

        // Setup for the @Embeddable class itself
        when(embeddableDeclaredType.asElement()).thenReturn(embeddableTypeElement);
        when(embeddableTypeElement.getAnnotation(Embeddable.class)).thenReturn(mock(Embeddable.class));
        when(embeddableTypeElement.getQualifiedName()).thenReturn(embeddableTypeName);
        when(embeddableTypeName.toString()).thenReturn("com.example.Address");

        // Setup for the simple field (e.g., String street) inside @Embeddable
        when(simpleFieldInEmbeddable.getKind()).thenReturn(javax.lang.model.element.ElementKind.FIELD);
        when(simpleFieldInEmbeddable.getSimpleName()).thenReturn(simpleFieldName);
        when(simpleFieldName.toString()).thenReturn("street");

        // Setup for the relationship field (e.g., Country country) inside @Embeddable
        when(relationshipFieldInEmbeddable.getKind()).thenReturn(javax.lang.model.element.ElementKind.FIELD);
        when(relationshipFieldInEmbeddable.getSimpleName()).thenReturn(relationshipFieldName);
        when(relationshipFieldName.toString()).thenReturn("country");
        when(relationshipFieldInEmbeddable.getAnnotation(ManyToOne.class)).thenReturn(mock(ManyToOne.class));
    }

    @Test
    @DisplayName("Should process a simple field within an embeddable type")
    void processEmbedded_WithSimpleField_AddsColumn() {
        // Given
        doReturn(List.of(simpleFieldInEmbeddable)).when(embeddableTypeElement).getEnclosedElements();
        ColumnModel streetColumn = ColumnModel.builder().columnName("street").build();
        when(columnHandler.createFrom(eq(simpleFieldInEmbeddable), any())).thenReturn(streetColumn);

        // When
        embeddedHandler.processEmbedded(embeddedField, columns, relationships, processedTypes);

        // Then
        assertThat(columns).hasSize(1);
        assertThat(columns).containsKey("street");
        assertThat(columns.get("street")).isEqualTo(streetColumn);
    }

    @Test
    @DisplayName("Should apply @AttributeOverride to a simple field")
    void processEmbedded_WithAttributeOverride_AddsOverriddenColumn() {
        // Given
        AttributeOverride override = mock(AttributeOverride.class);
        Column column = mock(Column.class);
        when(override.name()).thenReturn("street");
        when(override.column()).thenReturn(column);
        when(column.name()).thenReturn("address_street");

        AttributeOverrides overrides = mock(AttributeOverrides.class);
        when(overrides.value()).thenReturn(new AttributeOverride[]{override});
        when(embeddedField.getAnnotation(AttributeOverrides.class)).thenReturn(overrides);

        doReturn(List.of(simpleFieldInEmbeddable)).when(embeddableTypeElement).getEnclosedElements();
        ColumnModel streetColumn = ColumnModel.builder().columnName("address_street").build();
        // The column handler is now expected to be called with the override map
        when(columnHandler.createFrom(eq(simpleFieldInEmbeddable), eq(Map.of("street", "address_street")))).thenReturn(streetColumn);

        // When
        embeddedHandler.processEmbedded(embeddedField, columns, relationships, processedTypes);

        // Then
        assertThat(columns).hasSize(1);
        assertThat(columns).containsKey("address_street");
    }

    @Test
    @DisplayName("Should process a relationship field within an embeddable type")
    void processEmbedded_WithRelationshipField_AddsForeignKeyAndRelationship() {
        // Given
        setupRelationshipMocks();
        ManyToOne m2o = mock(ManyToOne.class);
        when(m2o.cascade()).thenReturn(new CascadeType[0]);
        when(relationshipFieldInEmbeddable.getAnnotation(ManyToOne.class)).thenReturn(m2o);
        doReturn(List.of(relationshipFieldInEmbeddable)).when(embeddableTypeElement).getEnclosedElements();

        // When
        embeddedHandler.processEmbedded(embeddedField, columns, relationships, processedTypes);

        // Then
        assertThat(columns).hasSize(1);
        assertThat(columns).containsKey("country_id");
        assertThat(columns.get("country_id").getJavaType()).isEqualTo("java.lang.Long");

        assertThat(relationships).hasSize(1);
        RelationshipModel rel = relationships.get(0);
        assertThat(rel.getType()).isEqualTo(RelationshipType.MANY_TO_ONE);
        assertThat(rel.getColumn()).isEqualTo("country_id");
        assertThat(rel.getReferencedTable()).isEqualTo("countries");
    }

    @Test
    @DisplayName("Should apply @AssociationOverride to a relationship field")
    void processEmbedded_WithAssociationOverride_AddsOverriddenForeignKey() {
        // Given
        setupRelationshipMocks();
        doReturn(List.of(relationshipFieldInEmbeddable)).when(embeddableTypeElement).getEnclosedElements();

        AssociationOverride override = mock(AssociationOverride.class);
        JoinColumn joinColumn = mock(JoinColumn.class);
        when(override.name()).thenReturn("country");
        when(joinColumn.name()).thenReturn("country_fk_id");
        when(override.joinColumns()).thenReturn(new JoinColumn[]{joinColumn});

        when(embeddedField.getAnnotation(AssociationOverride.class)).thenReturn(override);

        // When
        embeddedHandler.processEmbedded(embeddedField, columns, relationships, processedTypes);

        // Then
        assertThat(columns).hasSize(1);
        // Assert that the overridden column name is used
        assertThat(columns).containsKey("country_fk_id");
        assertThat(relationships).hasSize(1);
        assertThat(relationships.get(0).getColumn()).isEqualTo("country_fk_id");
    }

    @Test
    @DisplayName("Should add prefix to columns when inside @ElementCollection")
    void processEmbedded_WithElementCollection_AddsPrefixedColumns() {
        // Given
        when(embeddedField.getAnnotation(ElementCollection.class)).thenReturn(mock(ElementCollection.class));
        doReturn(List.of(simpleFieldInEmbeddable)).when(embeddableTypeElement).getEnclosedElements();
        ColumnModel streetColumn = ColumnModel.builder().columnName("street").build();
        when(columnHandler.createFrom(eq(simpleFieldInEmbeddable), any())).thenReturn(streetColumn);

        // When
        embeddedHandler.processEmbedded(embeddedField, columns, relationships, processedTypes);

        // Then
        assertThat(columns).hasSize(1);
        // The column name should be prefixed with the embedding field's name
        assertThat(columns).containsKey("address_street");
    }

    // Helper method to set up common mocks for relationship tests
    private void setupRelationshipMocks() {
        DeclaredType relFieldType = mock(DeclaredType.class);
        TypeElement referencedEntityType = mock(TypeElement.class);
        Name referencedEntityName = mock(Name.class);
        EntityModel referencedEntityModel = mock(EntityModel.class);
        ColumnModel pkColumn = ColumnModel.builder().columnName("id").javaType("java.lang.Long").build();

        when(relationshipFieldInEmbeddable.asType()).thenReturn(relFieldType);
        when(relFieldType.asElement()).thenReturn(referencedEntityType);
        when(referencedEntityType.getQualifiedName()).thenReturn(referencedEntityName);
        when(referencedEntityName.toString()).thenReturn("com.example.Country");

        when(schemaModel.getEntities()).thenReturn(Map.of("com.example.Country", referencedEntityModel));
        when(context.findPrimaryKeyColumnName(referencedEntityModel)).thenReturn(Optional.of("id"));
        when(referencedEntityModel.getColumns()).thenReturn(Map.of("id", pkColumn));
        when(referencedEntityModel.getTableName()).thenReturn("countries");
    }
}
