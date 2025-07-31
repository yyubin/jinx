package org.jinx.handler;

import jakarta.persistence.*;
import org.jinx.context.ProcessingContext;
import org.jinx.model.*;
import org.jinx.util.ColumnUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;

import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("ElementCollectionHandler")
class ElementCollectionHandlerTest {

    @Mock private ProcessingContext context;
    @Mock private ColumnHandler columnHandler;
    @Mock private EmbeddedHandler embeddedHandler;
    @Mock private SchemaModel schemaModel;
    @Mock private Messager messager;
    @Mock private Types types;

    private ElementCollectionHandler handler;
    private EntityModel ownerEntity;
    private Map<String, EntityModel> entitiesMap;
    private MockedStatic<ColumnUtils> columnUtilsMockedStatic;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        handler = new ElementCollectionHandler(context, columnHandler, embeddedHandler);
        entitiesMap = spy(new HashMap<>());

        ownerEntity = EntityModel.builder().tableName("user").entityName("com.example.User").build();
        ownerEntity.getColumns().put("id", ColumnModel.builder().javaType("java.lang.Long").build());

        when(context.getSchemaModel()).thenReturn(schemaModel);
        when(context.getMessager()).thenReturn(messager);
        when(context.getTypeUtils()).thenReturn(types);
        when(schemaModel.getEntities()).thenReturn(entitiesMap);

        columnUtilsMockedStatic = Mockito.mockStatic(ColumnUtils.class);
    }

    @AfterEach
    void tearDown() {
        columnUtilsMockedStatic.close();
    }

    private VariableElement mockField(String name, String containerType, String... genericTypes) {
        VariableElement field = mock(VariableElement.class);
        Name fieldName = mock(Name.class);
        when(fieldName.toString()).thenReturn(name);
        when(field.getSimpleName()).thenReturn(fieldName);

        DeclaredType declaredType = mock(DeclaredType.class);
        when(field.asType()).thenReturn(declaredType);
        when(context.isSubtype(eq(declaredType), anyString())).thenAnswer(inv -> {
            String supertypeName = inv.getArgument(1, String.class);
            return containerType.equals(supertypeName);
        });

        List<DeclaredType> genericTypeMirrors = new java.util.ArrayList<>();
        for (String genericType : genericTypes) {
            DeclaredType genericMirror = mock(DeclaredType.class);
            when(genericMirror.toString()).thenReturn(genericType);
            genericTypeMirrors.add(genericMirror);
        }
        doReturn(genericTypeMirrors).when(declaredType).getTypeArguments();

        return field;
    }

    @Test
    @DisplayName("기본 List<String> 컬렉션을 처리하여 컬렉션 테이블을 생성해야 한다")
    void process_withBasicList() {
        // Arrange
        when(context.findPrimaryKeyColumnName(ownerEntity)).thenReturn(Optional.of("id"));
        VariableElement field = mockField("tags", "java.util.List", "java.lang.String");

        ColumnModel elementColumn = ColumnModel.builder().columnName("tags").javaType("java.lang.String").build();
        when(columnHandler.createFromFieldType(eq(field), any(), eq("tags"))).thenReturn(elementColumn);

        // Act
        handler.processElementCollection(field, ownerEntity);

        // Assert
        ArgumentCaptor<EntityModel> entityCaptor = ArgumentCaptor.forClass(EntityModel.class);
        verify(schemaModel.getEntities()).putIfAbsent(eq("user_tags"), entityCaptor.capture());

        EntityModel collectionEntity = entityCaptor.getValue();
        assertThat(collectionEntity.getTableName()).isEqualTo("user_tags");
        assertThat(collectionEntity.getColumns()).hasSize(2);
        assertThat(collectionEntity.getColumns().get("user_id").getJavaType()).isEqualTo("java.lang.Long");
        assertThat(collectionEntity.getColumns().get("tags").getJavaType()).isEqualTo("java.lang.String");
        assertThat(collectionEntity.getRelationships()).hasSize(1);
        assertThat(collectionEntity.getRelationships().get(0).getReferencedTable()).isEqualTo("user");
    }

    @Test
    @DisplayName("Embeddable 타입 컬렉션은 EmbeddedHandler에게 처리를 위임해야 한다")
    void process_withEmbeddableCollection_shouldDelegateToEmbeddedHandler() {
        // Arrange
        when(context.findPrimaryKeyColumnName(ownerEntity)).thenReturn(Optional.of("id"));
        VariableElement field = mockField("addresses", "java.util.Set", "com.example.Address");

        // valueType이 @Embeddable을 가졌다고 가정
        Element valueElement = mock(TypeElement.class);
        when(types.asElement(any())).thenReturn(valueElement);
        when(valueElement.getAnnotation(Embeddable.class)).thenReturn(mock(Embeddable.class));

        // Act
        handler.processElementCollection(field, ownerEntity);

        // Assert
        // EmbeddedHandler의 메서드가 호출되었는지 검증
        verify(embeddedHandler).processEmbeddableFields(any(TypeElement.class), anyMap(), anyList(), anySet(), isNull(), eq(field));
        // 기본 타입 컬럼 생성 로직은 호출되지 않아야 함
        verify(columnHandler, never()).createFromFieldType(any(), any(), anyString());
    }

    @Test
    @DisplayName("Map<Enum, String> 타입과 @MapKeyEnumerated를 처리해야 한다")
    void process_withMapAndMapKeyEnumerated() {
        // Arrange
        when(context.findPrimaryKeyColumnName(ownerEntity)).thenReturn(Optional.of("id"));
        VariableElement field = mockField("metadata", "java.util.Map", "com.example.KeyType", "java.lang.String");

        // @MapKeyEnumerated 모의 설정
        MapKeyEnumerated mapKeyEnum = mock(MapKeyEnumerated.class);
        when(mapKeyEnum.value()).thenReturn(EnumType.STRING);
        when(field.getAnnotation(MapKeyEnumerated.class)).thenReturn(mapKeyEnum);

        // ColumnHandler가 Key와 Value에 대한 ColumnModel을 반환하도록 설정
        ColumnModel keyColumn = ColumnModel.builder().columnName("metadata_KEY").javaType("com.example.KeyType").build();
        ColumnModel valueColumn = ColumnModel.builder().columnName("metadata").javaType("java.lang.String").build();
        when(columnHandler.createFromFieldType(eq(field), any(), eq("metadata_KEY"))).thenReturn(keyColumn);
        when(columnHandler.createFromFieldType(eq(field), any(), eq("metadata"))).thenReturn(valueColumn);

        // getEnumConstants가 값을 반환하도록 설정
        columnUtilsMockedStatic.when(() -> ColumnUtils.getEnumConstants(any())).thenReturn(new String[]{"TYPE1", "TYPE2"});

        // Act
        handler.processElementCollection(field, ownerEntity);

        // Assert
        EntityModel collectionEntity = entitiesMap.get("user_metadata");
        assertThat(collectionEntity).isNotNull();
        assertThat(collectionEntity.getColumns()).hasSize(3); // FK, Map Key, Map Value
        ColumnModel capturedKeyColumn = collectionEntity.getColumns().get("metadata_KEY");
        assertThat(capturedKeyColumn.isMapKey()).isTrue();
        assertThat(capturedKeyColumn.isEnumStringMapping()).isTrue();
        assertThat(capturedKeyColumn.getEnumValues()).containsExactly("TYPE1", "TYPE2");
    }

    @Test
    @DisplayName("소유자 엔티티에 PK가 없으면 에러를 기록하고 처리를 중단해야 한다")
    void process_shouldLogError_whenOwnerHasNoPk() {
        // Arrange
        when(context.findPrimaryKeyColumnName(ownerEntity)).thenReturn(Optional.empty());
        VariableElement field = mockField("tags", "java.util.List", "java.lang.String");

        // Act
        handler.processElementCollection(field, ownerEntity);

        // Assert
        // 에러 메시지가 출력되었는지 확인
        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR), anyString(), eq(field));
        // 새 엔티티가 스키마에 추가되지 않았는지 확인
        assertThat(entitiesMap).isEmpty();
    }
}