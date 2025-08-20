//package org.jinx.handler;
//
//import jakarta.persistence.*;
//import org.jinx.context.ProcessingContext;
//import org.jinx.model.*;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.DisplayName;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.extension.ExtendWith;
//import org.mockito.*;
//import org.mockito.exceptions.base.MockitoException;
//
//import javax.annotation.processing.Messager;
//import javax.lang.model.element.*;
//import javax.lang.model.type.DeclaredType;
//import javax.lang.model.type.TypeMirror;
//import javax.lang.model.element.Name;
//import javax.tools.Diagnostic;
//import java.util.*;
//
//import static com.google.common.truth.Truth.assertThat;
//import static org.mockito.Mockito.*;
//
//class RelationshipHandlerTest {
//
//    @Mock
//    private ProcessingContext context;
//    @Mock
//    private SchemaModel schemaModel;
//    @Mock
//    private Messager messager;
//    @Mock
//    private TypeElement ownerTypeElement;
//    @Mock
//    private VariableElement fieldElement;
//    @Mock
//    private DeclaredType fieldType;
//    @Mock
//    private TypeElement referencedTypeElement;
//    @Mock
//    private Name referencedElementName;
//    @Mock
//    private Name fieldElementName;
//
//    @Captor
//    private ArgumentCaptor<EntityModel> entityModelCaptor;
//    @Captor
//    private ArgumentCaptor<String> stringCaptor;
//    @Captor
//    private ArgumentCaptor<RelationshipModel> relationshipCaptor;
//
//    private RelationshipHandler relationshipHandler;
//    private EntityModel ownerEntityModel;
//    private EntityModel referencedEntityModel;
//
//    @BeforeEach
//    void setUp() {
//        MockitoAnnotations.openMocks(this);
//
//        relationshipHandler = new RelationshipHandler(context);
//
//        when(context.getSchemaModel()).thenReturn(schemaModel);
//        lenient().when(context.getMessager()).thenReturn(messager);
//
//        ownerEntityModel = EntityModel.builder()
//                .entityName("Owner")
//                .tableName("owner_table")
//                .columns(new java.util.HashMap<>())
//                .relationships(new java.util.ArrayList<>())
//                .build();
//        doReturn(List.of(fieldElement)).when(ownerTypeElement).getEnclosedElements();
//        when(fieldElement.getKind()).thenReturn(ElementKind.FIELD);
//
//        referencedEntityModel = EntityModel.builder()
//                .entityName("Referenced")
//                .tableName("referenced_table")
//                .columns(new java.util.HashMap<>())
//                .build();
//        ColumnModel referencedPkColumn = ColumnModel.builder().columnName("id").javaType("java.lang.Long").isPrimaryKey(true).build();
//        referencedEntityModel.getColumns().put("id", referencedPkColumn);
//
//        lenient().when(referencedElementName.toString()).thenReturn("com.example.Referenced");
//        lenient().when(fieldElementName.toString()).thenReturn("referenced");
//        lenient().when(referencedTypeElement.getQualifiedName()).thenReturn(referencedElementName);
//        lenient().when(fieldElement.getSimpleName()).thenReturn(fieldElementName);
//        lenient().when(schemaModel.getEntities()).thenReturn(Map.of("com.example.Referenced", referencedEntityModel));
//        lenient().when(context.findPrimaryKeyColumnName(referencedEntityModel)).thenReturn(Optional.of("id"));
//    }
//
//    private void setupFieldType() {
//        when(fieldElement.asType()).thenReturn(fieldType);
//        when(fieldType.asElement()).thenReturn(referencedTypeElement);
//    }
//
//    @Test
//    @DisplayName("@ManyToOne 관계를 올바르게 처리해야 한다")
//    void resolveRelationships_WithManyToOne_ShouldCreateForeignKeyAndRelationship() {
//        // Given
//        setupFieldType();
//        ManyToOne manyToOne = mock(ManyToOne.class);
//        JoinColumn joinColumn = mock(JoinColumn.class);
//        when(fieldElement.getAnnotation(ManyToOne.class)).thenReturn(manyToOne);
//        when(fieldElement.getAnnotation(JoinColumn.class)).thenReturn(joinColumn);
//        when(joinColumn.name()).thenReturn(""); // 기본 이름 생성 규칙 테스트
//        when(manyToOne.optional()).thenReturn(true);
//        when(manyToOne.fetch()).thenReturn(FetchType.EAGER);
//        when(manyToOne.cascade()).thenReturn(new CascadeType[]{});
//
//        // When
//        relationshipHandler.resolveRelationships(ownerTypeElement, ownerEntityModel);
//
//        // Then
//        assertThat(ownerEntityModel.getColumns()).hasSize(1);
//        ColumnModel fkColumn = ownerEntityModel.getColumns().get("referenced_id");
//        assertThat(fkColumn).isNotNull();
//        assertThat(fkColumn.getColumnName()).isEqualTo("referenced_id");
//        assertThat(fkColumn.getJavaType()).isEqualTo("java.lang.Long");
//        assertThat(fkColumn.isPrimaryKey()).isFalse();
//        assertThat(fkColumn.isNullable()).isTrue();
//
//        assertThat(ownerEntityModel.getRelationships()).hasSize(1);
//        RelationshipModel relationship = ownerEntityModel.getRelationships().get(0);
//        assertThat(relationship.getType()).isEqualTo(RelationshipType.MANY_TO_ONE);
//        assertThat(relationship.getColumn()).isEqualTo("referenced_id");
//        assertThat(relationship.getReferencedTable()).isEqualTo("referenced_table");
//        assertThat(relationship.getReferencedColumn()).isEqualTo("id");
//        assertThat(relationship.getFetchType()).isEqualTo(FetchType.EAGER);
//    }
//
//    @Test
//    @DisplayName("@OneToOne(unique=true) 관계를 올바르게 처리해야 한다")
//    void resolveRelationships_WithOneToOne_ShouldCreateUniqueForeignKey() {
//        // Given
//        setupFieldType();
//        OneToOne oneToOne = mock(OneToOne.class);
//        JoinColumn joinColumn = mock(JoinColumn.class);
//        when(fieldElement.getAnnotation(OneToOne.class)).thenReturn(oneToOne);
//        when(fieldElement.getAnnotation(JoinColumn.class)).thenReturn(joinColumn);
//        when(joinColumn.name()).thenReturn("ref_custom_id"); // 커스텀 이름 테스트
//        when(oneToOne.optional()).thenReturn(false);
//        when(oneToOne.fetch()).thenReturn(FetchType.LAZY);
//        when(oneToOne.cascade()).thenReturn(new CascadeType[]{});
//
//        // When
//        relationshipHandler.resolveRelationships(ownerTypeElement, ownerEntityModel);
//
//        // Then
//        assertThat(ownerEntityModel.getColumns()).hasSize(1);
//        ColumnModel fkColumn = ownerEntityModel.getColumns().get("ref_custom_id");
//        assertThat(fkColumn).isNotNull();
//        assertThat(fkColumn.isUnique()).isTrue();
//        assertThat(fkColumn.isNullable()).isFalse();
//
//        assertThat(ownerEntityModel.getRelationships()).hasSize(1);
//        RelationshipModel relationship = ownerEntityModel.getRelationships().get(0);
//        assertThat(relationship.getType()).isEqualTo(RelationshipType.ONE_TO_ONE);
//        assertThat(relationship.getColumn()).isEqualTo("ref_custom_id");
//    }
//
//    @Test
//    @DisplayName("@OneToOne 과 @MapsId 를 사용한 주키-외래키 관계를 올바르게 처리해야 한다")
//    void resolveRelationships_WithOneToOneAndMapsId_ShouldCreatePrimaryKeyForeignKey() {
//        // Given
//        setupFieldType();
//        OneToOne oneToOne = mock(OneToOne.class);
//        JoinColumn joinColumn = mock(JoinColumn.class);
//        MapsId mapsId = mock(MapsId.class);
//        when(fieldElement.getAnnotation(OneToOne.class)).thenReturn(oneToOne);
//        when(fieldElement.getAnnotation(JoinColumn.class)).thenReturn(joinColumn);
//        when(fieldElement.getAnnotation(MapsId.class)).thenReturn(mapsId);
//        when(joinColumn.name()).thenReturn("");
//        when(oneToOne.fetch()).thenReturn(FetchType.LAZY);
//        when(oneToOne.cascade()).thenReturn(new CascadeType[]{});
//
//        // When
//        relationshipHandler.resolveRelationships(ownerTypeElement, ownerEntityModel);
//
//        // Then
//        assertThat(ownerEntityModel.getColumns()).hasSize(1);
//        ColumnModel fkColumn = ownerEntityModel.getColumns().get("referenced_id");
//        assertThat(fkColumn).isNotNull();
//        assertThat(fkColumn.isPrimaryKey()).isTrue();
//        assertThat(fkColumn.isNullable()).isFalse(); // @MapsId implies non-nullable
//
//        assertThat(ownerEntityModel.getRelationships()).hasSize(1);
//        RelationshipModel relationship = ownerEntityModel.getRelationships().get(0);
//        assertThat(relationship.isMapsId()).isTrue();
//    }
//
//    @Test
//    @DisplayName("@OneToMany(단방향) 관계를 올바르게 처리해야 한다")
//    void resolveRelationships_WithUnidirectionalOneToMany_ShouldCreateRelationship() {
//        // Given
//        DeclaredType listType = mock(DeclaredType.class);
//        DeclaredType genericType = mock(DeclaredType.class);
//
//        when(fieldElement.asType()).thenReturn(listType);              // field.asType()은 List<Referenced> 타입을 반환
//        doReturn(List.of(genericType)).when(listType).getTypeArguments();
//        when(genericType.asElement()).thenReturn(referencedTypeElement);    // Referenced 타입의 Element는 referencedTypeElement
//
//        OneToMany oneToMany = mock(OneToMany.class);
//        JoinColumn joinColumn = mock(JoinColumn.class);
//        when(fieldElement.getAnnotation(OneToMany.class)).thenReturn(oneToMany);
//        when(fieldElement.getAnnotation(JoinColumn.class)).thenReturn(joinColumn);
//
//        when(oneToMany.mappedBy()).thenReturn("");
//        when(joinColumn.name()).thenReturn("owner_id_in_referenced");
//        when(oneToMany.fetch()).thenReturn(FetchType.LAZY);
//        when(oneToMany.cascade()).thenReturn(new CascadeType[]{CascadeType.ALL});
//        when(oneToMany.orphanRemoval()).thenReturn(true);
//
//        // When
//        relationshipHandler.resolveRelationships(ownerTypeElement, ownerEntityModel);
//
//        // Then
//        assertThat(ownerEntityModel.getColumns()).isEmpty();
//
//        assertThat(ownerEntityModel.getRelationships()).hasSize(1);
//        RelationshipModel relationship = ownerEntityModel.getRelationships().get(0);
//        assertThat(relationship.getType()).isEqualTo(RelationshipType.ONE_TO_MANY);
//        assertThat(relationship.getColumn()).isEqualTo("owner_id_in_referenced");
//        assertThat(relationship.getReferencedTable()).isEqualTo("referenced_table");
//        assertThat(relationship.getCascadeTypes()).containsExactly(CascadeType.ALL);
//        assertThat(relationship.isOrphanRemoval()).isTrue();
//    }
//
//    @Test
//    @DisplayName("@ManyToMany 관계는 조인 테이블 엔티티를 생성해야 한다")
//    void resolveRelationships_WithManyToMany_ShouldCreateJoinTableEntity() {
//        // Given
//        // Mock a collection field: List<Referenced>
//        DeclaredType listType = mock(DeclaredType.class);
//        DeclaredType genericType = mock(DeclaredType.class);
//
//        when(fieldElement.asType()).thenReturn(listType);
//        doReturn(List.of(genericType)).when(listType).getTypeArguments();
//        when(genericType.asElement()).thenReturn(referencedTypeElement);
//
//        ManyToMany manyToMany = mock(ManyToMany.class);
//        JoinTable joinTable = mock(JoinTable.class);
//        when(manyToMany.mappedBy()).thenReturn("");
//        when(manyToMany.cascade()).thenReturn(new CascadeType[]{});
//        when(manyToMany.fetch()).thenReturn(FetchType.LAZY);
//        when(fieldElement.getAnnotation(ManyToMany.class)).thenReturn(manyToMany);
//        when(fieldElement.getAnnotation(JoinTable.class)).thenReturn(joinTable);
//
//        // Mock JoinTable annotation details
//        when(joinTable.name()).thenReturn("owner_referenced_join_table");
//        JoinColumn ownerJoinColumn = mock(JoinColumn.class);
//        when(ownerJoinColumn.name()).thenReturn("owner_fk");
//        when(joinTable.joinColumns()).thenReturn(new JoinColumn[]{ownerJoinColumn});
//        JoinColumn inverseJoinColumn = mock(JoinColumn.class);
//        when(inverseJoinColumn.name()).thenReturn("referenced_fk");
//        when(joinTable.inverseJoinColumns()).thenReturn(new JoinColumn[]{inverseJoinColumn});
//
//        // Mock PK for owner entity
//        ColumnModel ownerPkColumn = ColumnModel.builder().columnName("owner_pk").javaType("java.lang.Integer").isPrimaryKey(true).build();
//        ownerEntityModel.getColumns().put("owner_pk", ownerPkColumn);
//        when(context.findPrimaryKeyColumnName(ownerEntityModel)).thenReturn(Optional.of("owner_pk"));
//
//        // schemaModel.getEntities()가 호출될 때 putIfAbsent를 위해 수정 가능한 Map을 반환하도록 설정
//        Map<String, EntityModel> entitiesMap = Mockito.spy(new HashMap<>());
//        entitiesMap.put("com.example.Referenced", referencedEntityModel);
//        when(schemaModel.getEntities()).thenReturn(entitiesMap);
//
//        // When
//        relationshipHandler.resolveRelationships(ownerTypeElement, ownerEntityModel);
//
//        // Then
//        // Verify a new entity for the join table was created and added to the schema
//        verify(schemaModel.getEntities()).putIfAbsent(stringCaptor.capture(), entityModelCaptor.capture());
//
//        assertThat(stringCaptor.getValue()).isEqualTo("owner_referenced_join_table");
//        EntityModel joinTableEntity = entityModelCaptor.getValue();
//
//        assertThat(joinTableEntity.getTableName()).isEqualTo("owner_referenced_join_table");
//        assertThat(joinTableEntity.getTableType()).isEqualTo(EntityModel.TableType.JOIN_TABLE);
//
//        // 조인 테이블의 컬럼 검증
//        assertThat(joinTableEntity.getColumns()).hasSize(2);
//        ColumnModel ownerFk = joinTableEntity.getColumns().get("owner_fk");
//        assertThat(ownerFk).isNotNull();
//        assertThat(ownerFk.getJavaType()).isEqualTo("java.lang.Integer");
//        assertThat(ownerFk.isPrimaryKey()).isTrue();
//
//        ColumnModel referencedFk = joinTableEntity.getColumns().get("referenced_fk");
//        assertThat(referencedFk).isNotNull();
//        assertThat(referencedFk.getJavaType()).isEqualTo("java.lang.Long");
//        assertThat(referencedFk.isPrimaryKey()).isTrue();
//
//        // 조인 테이블의 관계 검증
//        assertThat(joinTableEntity.getRelationships()).hasSize(2);
//        RelationshipModel toOwner = joinTableEntity.getRelationships().stream()
//                .filter(r -> r.getReferencedTable().equals("owner_table")).findFirst().orElse(null);
//        assertThat(toOwner).isNotNull();
//        assertThat(toOwner.getColumn()).isEqualTo("owner_fk");
//        assertThat(toOwner.getReferencedColumn()).isEqualTo("owner_pk");
//
//        RelationshipModel toReferenced = joinTableEntity.getRelationships().stream()
//                .filter(r -> r.getReferencedTable().equals("referenced_table")).findFirst().orElse(null);
//        assertThat(toReferenced).isNotNull();
//        assertThat(toReferenced.getColumn()).isEqualTo("referenced_fk");
//        assertThat(toReferenced.getReferencedColumn()).isEqualTo("id");
//    }
//}