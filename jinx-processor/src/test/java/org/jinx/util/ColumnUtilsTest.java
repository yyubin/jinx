package org.jinx.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("ColumnUtils")
class ColumnUtilsTest {

    @Mock
    private DeclaredType mockDeclaredType;
    @Mock
    private TypeMirror mockNonDeclaredType;
    @Mock
    private TypeElement mockEnumElement;
    @Mock
    private TypeElement mockClassElement;

    private Element createMockEnumConstant(String name) {
        Element constant = mock(Element.class);
        Name simpleName = mock(Name.class);
        when(constant.getKind()).thenReturn(ElementKind.ENUM_CONSTANT);
        when(constant.getSimpleName()).thenReturn(simpleName);
        when(simpleName.toString()).thenReturn(name);
        return constant;
    }

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    @DisplayName("유효한 Enum 타입이 주어지면 상수 이름 배열을 반환해야 한다")
    void getEnumConstants_ForValidEnum_ReturnsConstantNames() {
        // Arrange
        when(mockDeclaredType.asElement()).thenReturn(mockEnumElement);
        when(mockEnumElement.getKind()).thenReturn(ElementKind.ENUM);

        Element constant1 = createMockEnumConstant("PENDING");
        Element constant2 = createMockEnumConstant("COMPLETED");
        // ENUM_CONSTANT가 아닌 다른 종류의 Element는 필터링되어야 함
        Element methodElement = mock(Element.class);
        when(methodElement.getKind()).thenReturn(ElementKind.METHOD);

        doReturn(List.of(constant1, methodElement, constant2)).when(mockEnumElement).getEnclosedElements();

        // Act
        String[] constants = ColumnUtils.getEnumConstants(mockDeclaredType);

        // Assert
        assertThat(constants).containsExactly("PENDING", "COMPLETED");
    }

    @Test
    @DisplayName("Enum이 아닌 타입(예: Class)이 주어지면 빈 배열을 반환해야 한다")
    void getEnumConstants_ForNonEnumType_ReturnsEmptyArray() {
        // Arrange
        when(mockDeclaredType.asElement()).thenReturn(mockClassElement);
        when(mockClassElement.getKind()).thenReturn(ElementKind.CLASS);

        // Act
        String[] constants = ColumnUtils.getEnumConstants(mockDeclaredType);

        // Assert
        assertThat(constants).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("DeclaredType이 아닌 타입(예: Primitive)이 주어지면 빈 배열을 반환해야 한다")
    void getEnumConstants_ForNonDeclaredType_ReturnsEmptyArray() {
        // Act
        // mockNonDeclaredType은 DeclaredType의 인스턴스가 아니므로 첫 번째 if문에서 걸러짐
        String[] constants = ColumnUtils.getEnumConstants(mockNonDeclaredType);

        // Assert
        assertThat(constants).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("상수가 없는 Enum 타입이 주어지면 빈 배열을 반환해야 한다")
    void getEnumConstants_ForEnumWithNoConstants_ReturnsEmptyArray() {
        // Arrange
        when(mockDeclaredType.asElement()).thenReturn(mockEnumElement);
        when(mockEnumElement.getKind()).thenReturn(ElementKind.ENUM);
        when(mockEnumElement.getEnclosedElements()).thenReturn(Collections.emptyList());

        // Act
        String[] constants = ColumnUtils.getEnumConstants(mockDeclaredType);

        // Assert
        assertThat(constants).isNotNull().isEmpty();
    }
}