package org.jinx.handler;

import jakarta.persistence.*;
import org.jinx.context.ProcessingContext;
import org.jinx.model.ColumnModel;
import org.jinx.model.EntityModel;
import org.jinx.testing.mother.EntityModelMother;
import org.jinx.testing.util.AnnotationProxies;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.annotation.processing.Messager;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InheritanceHandlerDiscriminatorTest {

    @Mock private ProcessingContext context;
    @Mock private Messager messager;
    @Mock private Elements elements;
    @Mock private Types types;
    @Mock private org.jinx.model.SchemaModel schemaModel;

    private InheritanceHandler handler;
    private Map<String, EntityModel> entities;

    @BeforeEach
    void setUp() {
        handler = new InheritanceHandler(context);
        lenient().when(context.getMessager()).thenReturn(messager);
        lenient().when(context.getElementUtils()).thenReturn(elements);
        lenient().when(context.getTypeUtils()).thenReturn(types);
        lenient().when(context.getSchemaModel()).thenReturn(schemaModel);
        entities = new HashMap<>();
        lenient().when(schemaModel.getEntities()).thenReturn(entities);
    }

    // ---- helpers ----
    private TypeElement mockType(String fqcn) {
        TypeElement te = mock(TypeElement.class);
        Name qn = mock(Name.class);
        lenient().when(qn.toString()).thenReturn(fqcn);
        lenient().when(te.getQualifiedName()).thenReturn(qn);
        lenient().when(te.asType()).thenReturn(mock(TypeMirror.class));
        return te;
    }

    private EntityModel newEntity(String fqcn, String table) {
        EntityModel em = EntityModelMother.javaEntity(fqcn, table);
        entities.put(em.getEntityName(), em);
        return em;
    }

    private ColumnModel getDisc(EntityModel e, String name) {
        return e.findColumn(e.getTableName(), name);
    }

    // === CASES ===

    @Test
    @DisplayName("SINGLE_TABLE: @DiscriminatorColumn 없으면 기본 DTYPE(String, len=31) 생성")
    void singleTable_default_created_when_missing_annotation() {
        TypeElement type = mockType("com.example.St");
        when(type.getAnnotation(Inheritance.class))
                .thenReturn(AnnotationProxies.inheritance(InheritanceType.SINGLE_TABLE));

        EntityModel e = newEntity("com.example.St", "st");

        handler.resolveInheritance(type, e);

        ColumnModel col = getDisc(e, "DTYPE");
        assertNotNull(col);
        assertEquals("java.lang.String", col.getJavaType());
        assertFalse(col.isNullable());
        assertEquals(ColumnModel.ColumnKind.DISCRIMINATOR, col.getColumnKind());
        assertEquals(DiscriminatorType.STRING, col.getDiscriminatorType());
        assertEquals(31, col.getLength());
        assertNull(col.getColumnDefinition());
        assertNull(col.getOptions());
    }

    @Test
    @DisplayName("JOINED: @DiscriminatorColumn 없으면 생성하지 않음")
    void joined_missing_discriminator_not_created() {
        TypeElement type = mockType("com.example.J");
        when(type.getAnnotation(Inheritance.class))
                .thenReturn(AnnotationProxies.inheritance(InheritanceType.JOINED));

        // JOINED는 자식 탐색이 있어서 빈 엔티티 맵으로 돌려 부수효과 방지
        lenient().when(schemaModel.getEntities()).thenReturn(new HashMap<>());

        EntityModel e = newEntity("com.example.J", "j");
        handler.resolveInheritance(type, e);

        assertNull(getDisc(e, "DTYPE"));
    }

    @Test
    @DisplayName("JOINED: @DiscriminatorColumn 지정 시 컬럼 생성")
    void joined_with_discriminator_created() {
        TypeElement type = mockType("com.example.J2");
        when(type.getAnnotation(Inheritance.class))
                .thenReturn(AnnotationProxies.inheritance(InheritanceType.JOINED));
        when(type.getAnnotation(DiscriminatorColumn.class))
                .thenReturn(AnnotationProxies.discriminatorColumnFull("DISC", DiscriminatorType.STRING, 20, "", ""));

        // 자식 탐색 방지
        lenient().when(schemaModel.getEntities()).thenReturn(new HashMap<>());

        EntityModel e = newEntity("com.example.J2", "j2");
        handler.resolveInheritance(type, e);

        ColumnModel col = getDisc(e, "DISC");
        assertNotNull(col);
        assertEquals("java.lang.String", col.getJavaType());
        assertEquals(20, col.getLength());
        assertEquals(DiscriminatorType.STRING, col.getDiscriminatorType());
    }

    @Test
    @DisplayName("columnDefinition + options 동시 지정 → ERROR & 미생성")
    void discriminator_columnDef_and_options_both_set_error() {
        TypeElement type = mockType("com.example.Err");
        when(type.getAnnotation(Inheritance.class))
                .thenReturn(AnnotationProxies.inheritance(InheritanceType.SINGLE_TABLE));
        when(type.getAnnotation(DiscriminatorValue.class)).thenReturn(null);
        when(type.getAnnotation(DiscriminatorColumn.class))
                .thenReturn(AnnotationProxies.discriminatorColumnFull(
                        "DISC", DiscriminatorType.STRING, 20, "varchar(20)", "COLLATE utf8"));

        EntityModel e = newEntity("com.example.Err", "err");
        handler.resolveInheritance(type, e);

        assertNull(getDisc(e, "DISC"));
        assertFalse(e.isValid());
        verify(messager, atLeastOnce())
                .printMessage(eq(Diagnostic.Kind.ERROR), contains("cannot be used together"), eq(type));
    }

    @Test
    @DisplayName("CHAR + length != 1 → WARNING 후 length=1로 보정")
    void discriminator_char_length_adjusted_with_warning() {
        TypeElement type = mockType("com.example.Char");
        when(type.getAnnotation(Inheritance.class))
                .thenReturn(AnnotationProxies.inheritance(InheritanceType.SINGLE_TABLE));
        when(type.getAnnotation(DiscriminatorValue.class)).thenReturn(null);
        when(type.getAnnotation(DiscriminatorColumn.class))
                .thenReturn(AnnotationProxies.discriminatorColumnFull("C", DiscriminatorType.CHAR, 2, "", ""));

        EntityModel e = newEntity("com.example.Char", "ch");
        handler.resolveInheritance(type, e);

        ColumnModel col = getDisc(e, "C");
        assertNotNull(col);
        assertEquals("java.lang.String", col.getJavaType()); // CHAR도 String 매핑
        assertEquals(1, col.getLength());
        assertEquals(DiscriminatorType.CHAR, col.getDiscriminatorType());
        verify(messager, atLeastOnce())
                .printMessage(eq(Diagnostic.Kind.WARNING), contains("adjusted to 1"), eq(type));
    }

    @Test
    @DisplayName("STRING/CHAR + length <= 0 → ERROR & 미생성")
    void discriminator_invalid_length_error() {
        TypeElement type = mockType("com.example.Len");
        when(type.getAnnotation(Inheritance.class))
                .thenReturn(AnnotationProxies.inheritance(InheritanceType.SINGLE_TABLE));
        when(type.getAnnotation(DiscriminatorValue.class)).thenReturn(null);
        when(type.getAnnotation(DiscriminatorColumn.class))
                .thenReturn(AnnotationProxies.discriminatorColumnFull("S", DiscriminatorType.STRING, 0, "", ""));

        EntityModel e = newEntity("com.example.Len", "len");
        handler.resolveInheritance(type, e);

        assertNull(getDisc(e, "S"));
        assertFalse(e.isValid());
        verify(messager, atLeastOnce())
                .printMessage(eq(Diagnostic.Kind.ERROR), contains("Invalid discriminator length"), eq(type));
    }

    @Test
    @DisplayName("중복 컬럼명(DTYPE 등) → ERROR & 미생성")
    void discriminator_duplicate_name_error() {
        TypeElement type = mockType("com.example.Dup");
        when(type.getAnnotation(Inheritance.class))
                .thenReturn(AnnotationProxies.inheritance(InheritanceType.SINGLE_TABLE));
        // 애노테이션 없이 기본 DTYPE을 만들려고 할 때, 동일명 컬럼을 미리 심어둔다
        EntityModel e = newEntity("com.example.Dup", "dup");
        e.putColumn(ColumnModel.builder()
                .tableName("dup").columnName("DTYPE").javaType("java.lang.String").isNullable(false).build());

        handler.resolveInheritance(type, e);

        // 이미 존재 → 에러, invalid
        verify(messager, atLeastOnce())
                .printMessage(eq(Diagnostic.Kind.ERROR), contains("Duplicate column name 'DTYPE'"), eq(type));
        assertFalse(e.isValid());
    }
}
