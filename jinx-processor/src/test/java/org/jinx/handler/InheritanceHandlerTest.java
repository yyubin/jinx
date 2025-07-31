package org.jinx.handler;

import jakarta.persistence.*;
import org.jinx.context.ProcessingContext;
import org.jinx.model.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.util.List;
import java.util.Map;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.*;

class InheritanceHandlerTest {

    private ProcessingContext mockContext(SchemaModel model,
                                          Elements elements,
                                          Types types,
                                          Messager messager) {

        ProcessingEnvironment env = mock(ProcessingEnvironment.class);
        when(env.getElementUtils()).thenReturn(elements);
        when(env.getTypeUtils()).thenReturn(types);
        when(env.getMessager()).thenReturn(messager);
        return new ProcessingContext(env, model);
    }

    @Test
    void singleTable_addsDiscriminatorColumn() {
        EntityModel parent = EntityModel.builder()
                .entityName("com.example.Parent")
                .tableName("Parent")
                .build();

        SchemaModel schema = SchemaModel.builder()
                .entities(Map.of(parent.getEntityName(), parent))
                .build();

        TypeElement parentType = mock(TypeElement.class);

        Inheritance inh = mock(Inheritance.class);
        when(inh.strategy()).thenReturn(InheritanceType.SINGLE_TABLE);
        when(parentType.getAnnotation(Inheritance.class)).thenReturn(inh);

        DiscriminatorColumn disc = mock(DiscriminatorColumn.class);
        when(disc.name()).thenReturn("dtype");
        when(parentType.getAnnotation(DiscriminatorColumn.class)).thenReturn(disc);

        ProcessingContext ctx = mockContext(schema,
                mock(Elements.class),
                mock(Types.class),
                mock(Messager.class));

        InheritanceHandler handler = new InheritanceHandler(ctx);

        handler.resolveInheritance(parentType, parent);

        assertThat(parent.getInheritance()).isEqualTo(InheritanceType.SINGLE_TABLE);
        assertThat(parent.getColumns()).containsKey("dtype");
    }

    @Test
    void joinedInheritance_populatesChildEntity() {
        ColumnModel idCol = ColumnModel.builder()
                .columnName("id")
                .javaType("java.lang.Long")
                .isPrimaryKey(true)
                .build();

        EntityModel parent = EntityModel.builder()
                .entityName("com.example.Parent")
                .tableName("Parent")
                .columns(Map.of("id", idCol))
                .build();

        EntityModel child = EntityModel.builder()
                .entityName("com.example.Child")
                .tableName("Child")
                .build();

        SchemaModel schema = SchemaModel.builder()
                .entities(Map.of(
                        parent.getEntityName(), parent,
                        child.getEntityName(), child))
                .build();

        TypeElement parentType = mock(TypeElement.class);
        TypeElement childType  = mock(TypeElement.class);
        TypeMirror  parentTm   = mock(TypeMirror.class);
        TypeMirror  childTm    = mock(TypeMirror.class);

        when(parentType.asType()).thenReturn(parentTm);
        when(childType.asType()).thenReturn(childTm);

        Inheritance inh = mock(Inheritance.class);
        when(inh.strategy()).thenReturn(InheritanceType.JOINED);
        when(parentType.getAnnotation(Inheritance.class)).thenReturn(inh);

        Elements elements = mock(Elements.class);
        when(elements.getTypeElement("com.example.Child")).thenReturn(childType);
        when(elements.getTypeElement("com.example.Parent")).thenReturn(parentType);

        Types types = mock(Types.class);
        when(types.isSubtype(childTm, parentTm)).thenReturn(true);

        ProcessingContext ctx = mockContext(schema, elements, types, mock(Messager.class));
        InheritanceHandler handler = new InheritanceHandler(ctx);

        handler.resolveInheritance(parentType, parent);

        assertThat(child.getInheritance()).isEqualTo(InheritanceType.JOINED);
        assertThat(child.getParentEntity()).isEqualTo(parent.getEntityName());
        assertThat(child.getColumns()).containsKey("id");
        assertThat(child.getColumns().get("id").isPrimaryKey()).isTrue();
        assertThat(child.getRelationships()).isNotEmpty();
        RelationshipModel rel = child.getRelationships().get(0);
        assertThat(rel.getReferencedTable()).isEqualTo("Parent");
        assertThat(rel.getColumn()).isEqualTo("id");
    }

    @Test
    void singleTable_duplicateDiscriminatorColumnShouldTriggerError() {
        EntityModel parent = EntityModel.builder()
                .entityName("com.example.Parent")
                .tableName("Parent")
                .columns(Map.of("dtype", ColumnModel.builder().columnName("dtype").build()))
                .build();

        SchemaModel schema = SchemaModel.builder()
                .entities(Map.of(parent.getEntityName(), parent))
                .build();

        TypeElement parentType = mock(TypeElement.class);

        Inheritance inh = mock(Inheritance.class);
        when(inh.strategy()).thenReturn(InheritanceType.SINGLE_TABLE);
        when(parentType.getAnnotation(Inheritance.class)).thenReturn(inh);

        DiscriminatorColumn disc = mock(DiscriminatorColumn.class);
        when(disc.name()).thenReturn("dtype");
        when(parentType.getAnnotation(DiscriminatorColumn.class)).thenReturn(disc);

        Messager messager = mock(Messager.class);

        ProcessingContext ctx = mockContext(schema, mock(Elements.class), mock(Types.class), messager);
        InheritanceHandler handler = new InheritanceHandler(ctx);

        handler.resolveInheritance(parentType, parent);

        assertThat(parent.isValid()).isFalse();
    }

    @Nested
    @DisplayName("추가 커버리지 테스트")
    class CoverageIncreaseTests {

        @Test
        @DisplayName("[SINGLE_TABLE] @DiscriminatorValue 애너테이션을 처리해야 한다")
        void singleTable_shouldProcessDiscriminatorValue() {
            // Arrange
            EntityModel parent = EntityModel.builder().entityName("com.example.Parent").build();
            TypeElement parentType = mock(TypeElement.class);
            Inheritance inh = mock(Inheritance.class);
            when(inh.strategy()).thenReturn(InheritanceType.SINGLE_TABLE);
            when(parentType.getAnnotation(Inheritance.class)).thenReturn(inh);

            DiscriminatorValue dv = mock(DiscriminatorValue.class);
            when(dv.value()).thenReturn("PARENT_ENTITY");
            when(parentType.getAnnotation(DiscriminatorValue.class)).thenReturn(dv);

            ProcessingContext ctx = mockContext(null, null, null, null);
            InheritanceHandler handler = new InheritanceHandler(ctx);

            // Act
            handler.resolveInheritance(parentType, parent);

            // Assert
            assertThat(parent.getDiscriminatorValue()).isEqualTo("PARENT_ENTITY");
        }

        @Test
        @DisplayName("[JOINED] 부모 엔티티에 PK가 없으면 에러 처리되어야 한다")
        void joined_shouldInvalidateParent_whenNoPrimaryKey() {
            // Arrange
            EntityModel parent = EntityModel.builder().entityName("com.example.Parent").build(); // PK 컬럼 없음
            TypeElement parentType = mock(TypeElement.class);
            Inheritance inh = mock(Inheritance.class);
            when(inh.strategy()).thenReturn(InheritanceType.JOINED);
            when(parentType.getAnnotation(Inheritance.class)).thenReturn(inh);

            ProcessingContext ctx = mockContext(SchemaModel.builder().build(), null, null, null);
            InheritanceHandler handler = new InheritanceHandler(ctx);

            // Act
            handler.resolveInheritance(parentType, parent);

            // Assert
            assertThat(parent.isValid()).isFalse();
        }

        @Test
        @DisplayName("[JOINED] 자식 엔티티에 중복된 PK 컬럼이 있으면 에러 처리되어야 한다")
        void joined_shouldInvalidateChild_withDuplicatePkColumn() {
            // Arrange
            ColumnModel idCol = ColumnModel.builder().columnName("id").javaType("long").isPrimaryKey(true).build();
            EntityModel parent = EntityModel.builder().entityName("com.example.Parent").tableName("Parent").columns(Map.of("id", idCol)).build();
            EntityModel child = EntityModel.builder().entityName("com.example.Child").tableName("Child").columns(Map.of("id", mock(ColumnModel.class))).build(); // 중복 컬럼
            SchemaModel schema = SchemaModel.builder().entities(Map.of(parent.getEntityName(), parent, child.getEntityName(), child)).build();

            TypeElement parentType = mock(TypeElement.class);
            TypeElement childType = mock(TypeElement.class);
            when(parentType.asType()).thenReturn(mock(TypeMirror.class));
            when(childType.asType()).thenReturn(mock(TypeMirror.class));

            Inheritance inh = mock(Inheritance.class);
            when(inh.strategy()).thenReturn(InheritanceType.JOINED);
            when(parentType.getAnnotation(Inheritance.class)).thenReturn(inh);

            Elements elements = mock(Elements.class);
            when(elements.getTypeElement("com.example.Child")).thenReturn(childType);
            Types types = mock(Types.class);
            when(types.isSubtype(any(), any())).thenReturn(true);
            Messager messager = mock(Messager.class);
            ProcessingContext ctx = mockContext(schema, elements, types, messager);
            InheritanceHandler handler = new InheritanceHandler(ctx);

            // Act
            handler.resolveInheritance(parentType, parent);

            // Assert
            assertThat(child.isValid()).isFalse();
            verify(messager).printMessage(eq(Diagnostic.Kind.ERROR), anyString());
        }

        @Test
        @DisplayName("[TABLE_PER_CLASS] 부모 컬럼을 자식 엔티티에 복사해야 한다")
        void tablePerClass_shouldCopyParentColumnsToChild() {
            // Arrange
            ColumnModel idCol = ColumnModel.builder().columnName("id").javaType("long").isPrimaryKey(true).build();
            ColumnModel nameCol = ColumnModel.builder().columnName("name").javaType("String").build();
            EntityModel parent = EntityModel.builder().entityName("com.example.Parent").tableName("Parent").columns(Map.of("id", idCol, "name", nameCol)).build();
            EntityModel child = EntityModel.builder().entityName("com.example.Child").tableName("Child").build();
            SchemaModel schema = SchemaModel.builder().entities(Map.of(parent.getEntityName(), parent, child.getEntityName(), child)).build();

            TypeElement parentType = mock(TypeElement.class);
            TypeElement childType = mock(TypeElement.class);
            when(parentType.asType()).thenReturn(mock(TypeMirror.class));
            when(childType.asType()).thenReturn(mock(TypeMirror.class));

            Inheritance inh = mock(Inheritance.class);
            when(inh.strategy()).thenReturn(InheritanceType.TABLE_PER_CLASS);
            when(parentType.getAnnotation(Inheritance.class)).thenReturn(inh);

            Elements elements = mock(Elements.class);
            when(elements.getTypeElement("com.example.Child")).thenReturn(childType);
            Types types = mock(Types.class);
            when(types.isSubtype(any(), any())).thenReturn(true);
            ProcessingContext ctx = mockContext(schema, elements, types, mock(Messager.class));
            InheritanceHandler handler = new InheritanceHandler(ctx);

            // Act
            handler.resolveInheritance(parentType, parent);

            // Assert
            assertThat(child.getInheritance()).isEqualTo(InheritanceType.TABLE_PER_CLASS);
            assertThat(child.getColumns()).containsKey("id");
            assertThat(child.getColumns()).containsKey("name");
            assertThat(child.getColumns().get("id").isPrimaryKey()).isTrue();
        }

        @Test
        @DisplayName("[TABLE_PER_CLASS] IDENTITY 전략 사용 시 경고를 출력해야 한다")
        void tablePerClass_shouldWarnOnIdentityStrategy() {
            // Arrange
            EntityModel parent = EntityModel.builder().entityName("com.example.Parent").build();
            TypeElement parentType = mock(TypeElement.class);
            VariableElement idField = mock(VariableElement.class);
            when(idField.getKind()).thenReturn(ElementKind.FIELD);
            when(idField.getAnnotation(Id.class)).thenReturn(mock(Id.class));
            GeneratedValue gv = mock(GeneratedValue.class);
            when(gv.strategy()).thenReturn(GenerationType.IDENTITY);
            when(idField.getAnnotation(GeneratedValue.class)).thenReturn(gv);
            when(parentType.getEnclosedElements()).thenReturn((List) List.of(idField)); // Raw list for mock

            Inheritance inh = mock(Inheritance.class);
            when(inh.strategy()).thenReturn(InheritanceType.TABLE_PER_CLASS);
            when(parentType.getAnnotation(Inheritance.class)).thenReturn(inh);

            Messager messager = mock(Messager.class);
            ProcessingContext ctx = mockContext(SchemaModel.builder().build(), null, null, messager);
            InheritanceHandler handler = new InheritanceHandler(ctx);

            // Act
            handler.resolveInheritance(parentType, parent);

            // Assert
            verify(messager).printMessage(eq(Diagnostic.Kind.WARNING), anyString(), eq(idField));
        }
    }
}
