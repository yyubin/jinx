package org.jinx.handler;

import jakarta.persistence.Id;
import org.jinx.context.ProcessingContext;
import org.jinx.model.ColumnModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import javax.lang.model.element.Name;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("ColumnHandler")
class ColumnHandlerTest {

    @Mock
    private ProcessingContext context;
    @Mock
    private SequenceHandler sequenceHandler;
    @Mock
    private VariableElement mockField;
    @Mock
    private TypeMirror mockType;
    @Mock
    private Name mockName;

    private ColumnHandler columnHandler;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // ColumnHandler는 내부적으로 Resolver를 생성하므로,
        // 이 테스트에서는 ColumnHandler가 올바른 Resolver를 사용하는지 결과물을 통해 검증합니다.
        columnHandler = new ColumnHandler(context, sequenceHandler);
    }

    @Nested
    @DisplayName("createFrom 메서드는")
    class CreateFromTest {

        @Test
        @DisplayName("EntityFieldResolver를 사용하여 일반 엔티티 필드 정보를 생성해야 한다")
        void shouldUseEntityFieldResolver() {
            // Arrange: "@Id private Long id;" 필드를 모의(mock)로 설정
            when(mockField.getAnnotation(Id.class)).thenReturn(mock(Id.class)); // PK로 설정
            when(mockField.asType()).thenReturn(mockType);
            when(mockType.toString()).thenReturn("java.lang.Long");
            when(mockField.getSimpleName()).thenReturn(mockName);
            when(mockName.toString()).thenReturn("id");

            // Act
            ColumnModel result = columnHandler.createFrom(mockField, Collections.emptyMap());

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.getColumnName()).isEqualTo("id");
            assertThat(result.getJavaType()).isEqualTo("java.lang.Long");
            // EntityFieldResolver는 @Id를 isPrimaryKey로 변환하므로, 이 값으로 검증
            assertThat(result.isPrimaryKey()).isTrue();
        }

        @Test
        @DisplayName("overrides 맵을 사용하여 컬럼명을 재정의해야 한다")
        void shouldOverrideColumnName() {
            // Arrange
            when(mockField.asType()).thenReturn(mockType);
            when(mockType.toString()).thenReturn("java.lang.String");
            when(mockField.getSimpleName()).thenReturn(mockName);
            when(mockName.toString()).thenReturn("originalName");

            // Act
            ColumnModel result = columnHandler.createFrom(mockField, Map.of("originalName", "overridden_name"));

            // Assert
            assertThat(result.getColumnName()).isEqualTo("overridden_name");
        }
    }

    @Nested
    @DisplayName("createFromFieldType 메서드는")
    class CreateFromFieldTypeTest {

        @Test
        @DisplayName("CollectionElementResolver를 사용하여 컬렉션 요소 정보를 생성해야 한다")
        void shouldUseCollectionElementResolver() {
            // Arrange: @ElementCollection의 요소(String) 타입을 모의(mock)로 설정
            when(mockType.toString()).thenReturn("java.lang.String");
            // CollectionElementResolver는 @Id 애너테이션을 확인하지 않음
            lenient().when(mockField.getAnnotation(Id.class)).thenReturn(null);

            // Act
            ColumnModel result = columnHandler.createFromFieldType(mockField, mockType, "tags_element");

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.getColumnName()).isEqualTo("tags_element");
            assertThat(result.getJavaType()).isEqualTo("java.lang.String");
            // CollectionElementResolver는 isPrimaryKey를 false로 설정하므로, 이 값으로 검증
            assertThat(result.isPrimaryKey()).isFalse();
        }
    }
}