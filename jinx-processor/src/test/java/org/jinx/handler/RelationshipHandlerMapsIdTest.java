package org.jinx.handler;

import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import org.jinx.context.ProcessingContext;
import org.jinx.descriptor.AttributeDescriptor;
import org.jinx.model.ColumnModel;
import org.jinx.model.EntityModel;
import org.jinx.model.RelationshipModel;
import org.jinx.model.RelationshipType;
import org.jinx.testing.asserts.ColumnAssertions;
import org.jinx.testing.asserts.MessagerAssertions;
import org.jinx.testing.mother.AttributeDescriptorFactory;
import org.jinx.testing.mother.EntityModelMother;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.annotation.processing.Messager;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.tools.Diagnostic;
import java.util.List;
import java.util.Map;

import static org.jinx.testing.asserts.RelationshipAssertions.assertFk;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;


@ExtendWith(MockitoExtension.class)
class RelationshipHandlerMapsIdTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    ProcessingContext context;

    @Mock
    Messager messager;

    @Mock
    TypeElement ownerType;

    RelationshipHandler handler;

    @BeforeEach
    void setUp() {
        handler = new RelationshipHandler(context);
        when(context.getMessager()).thenReturn(messager);

        // pkName 호출로 인한 NPE 방지: 어떤 리스트가 와도 이름을 돌려주도록
        lenient().when(context.getNaming().pkName(anyString(), anyList()))
                .thenAnswer(inv -> "PK_" + inv.getArgument(0));
    }

    @Test
    void mapsId_noValue_promotesFkToPk_andBinds_allColumns_singleId() {
        // Entity: orders(user_id PK)
        EntityModel orders = EntityModel.builder()
                .entityName("com.example.Order")
                .fqcn("com.example.Order")
                .tableName("orders")
                .isValid(true)
                .build();
        ColumnModel userIdPk = ColumnModel.builder()
                .tableName("orders").columnName("user_id")
                .javaType("java.lang.Long")
                .isPrimaryKey(true).isNullable(false)
                .build();
        orders.putColumn(userIdPk);

        // Relationship FK: orders.user_id -> users.id (ManyToOne owning)
        RelationshipModel rel = RelationshipModel.builder()
                .type(RelationshipType.MANY_TO_ONE)
                .tableName("orders")
                .columns(List.of("user_id"))
                .referencedTable("users")
                .referencedColumns(List.of("id"))
                .constraintName("FK_orders_user_id_users")
                .sourceAttributeName("user")
                .build();
        orders.getRelationships().put(rel.getConstraintName(), rel);

        // Descriptor: @ManyToOne + @MapsId (no value)
        ManyToOne m2o = mock(ManyToOne.class);
        lenient().when(m2o.optional()).thenReturn(true);
        lenient().when(m2o.cascade()).thenReturn(new jakarta.persistence.CascadeType[0]);
        lenient().when(m2o.fetch()).thenReturn(jakarta.persistence.FetchType.EAGER);

        MapsId mapsId = mock(MapsId.class);
        when(mapsId.value()).thenReturn("");

        VariableElement diagElem = mock(VariableElement.class);
        AttributeDescriptor d = AttributeDescriptorFactory.withDiagnostic(
                AttributeDescriptorFactory.setOf("com.example.User", "user", m2o, mapsId),
                diagElem
        );

        // Context stubs
        when(context.getCachedDescriptors(ownerType)).thenReturn(List.of(d));
        when(context.findAllPrimaryKeyColumns(orders)).thenReturn(List.of(userIdPk));

        // Run
        handler.processMapsIdAttributes(ownerType, orders);

        // Assert: FK → PK 승격 + NOT NULL 유지
        ColumnAssertions.assertPkNonNull(orders, "orders::user_id", "java.lang.Long");

        // Relationship에 MapsId 바인딩 기록
        RelationshipModel saved = orders.getRelationships().get("FK_orders_user_id_users");
        assertNotNull(saved);
        assertEquals("", saved.getMapsIdKeyPath());
        assertEquals("user_id", saved.getMapsIdBindings().get("user_id"));
    }

    @Test
    void mapsId_withValue_partialMapping_onEmbeddedId_promotesAndBindsSubset() {
        // Entity: orders(id_a PK, id_b PK) — composite key
        EntityModel orders = EntityModel.builder()
                .entityName("com.example.Order")
                .fqcn("com.example.Order")
                .tableName("orders")
                .isValid(true)
                .build();
        ColumnModel idA = ColumnModel.builder()
                .tableName("orders").columnName("id_a")
                .javaType("java.lang.Long").isPrimaryKey(true).isNullable(false).build();
        ColumnModel idB = ColumnModel.builder()
                .tableName("orders").columnName("id_b")
                .javaType("java.lang.Long").isPrimaryKey(true).isNullable(false).build();
        orders.putColumn(idA);
        orders.putColumn(idB);

        // Relationship FK: orders.id_a -> users.id (ManyToOne owning)
        RelationshipModel rel = RelationshipModel.builder()
                .type(RelationshipType.MANY_TO_ONE)
                .tableName("orders")
                .columns(List.of("id_a"))
                .referencedTable("users")
                .referencedColumns(List.of("id"))
                .constraintName("FK_orders_id_a_users")
                .sourceAttributeName("customer")
                .build();
        orders.getRelationships().put(rel.getConstraintName(), rel);

        // Descriptor: @ManyToOne + @MapsId("idA")
        ManyToOne m2o = mock(ManyToOne.class);
        lenient().when(m2o.optional()).thenReturn(true);
        lenient().when(m2o.cascade()).thenReturn(new jakarta.persistence.CascadeType[0]);
        lenient().when(m2o.fetch()).thenReturn(jakarta.persistence.FetchType.EAGER);

        MapsId mapsId = mock(MapsId.class);
        when(mapsId.value()).thenReturn("idA");

        VariableElement diagElem = mock(VariableElement.class);
        AttributeDescriptor d = AttributeDescriptorFactory.withDiagnostic(
                AttributeDescriptorFactory.setOf("com.example.User", "customer", m2o, mapsId),
                diagElem
        );

        // Context stubs
        when(context.getCachedDescriptors(ownerType)).thenReturn(List.of(d));
        when(context.findAllPrimaryKeyColumns(orders)).thenReturn(List.of(idA, idB));
        // EmbeddedId의 속성 경로 → PK 컬럼 해석
        when(context.getPkColumnsForAttribute("com.example.Order", "idA"))
                .thenReturn(List.of("id_a"));

        // Run
        handler.processMapsIdAttributes(ownerType, orders);

        // Assert: id_a는 이미 PK였고 NOT NULL, 그대로 유지
        ColumnAssertions.assertPkNonNull(orders, "orders::id_a", "java.lang.Long");

        // Relationship에 부분 바인딩 기록
        RelationshipModel saved = orders.getRelationships().get("FK_orders_id_a_users");
        assertNotNull(saved);
        assertEquals("idA", saved.getMapsIdKeyPath());
        assertEquals("id_a", saved.getMapsIdBindings().get("id_a"));
    }

    @Test
    void mapsId_errors_whenFkOnSecondaryTable() {
        // Entity: orders (PK 존재)
        EntityModel orders = EntityModelMother.javaEntityWithPkIdLong("com.example.Order", "orders");

        // Relationship is on secondary table "orders_detail" (invalid for @MapsId)
        RelationshipModel rel = RelationshipModel.builder()
                .type(RelationshipType.MANY_TO_ONE)
                .tableName("orders_detail") // <-- not the owner primary table
                .columns(List.of("user_id"))
                .referencedTable("users")
                .referencedColumns(List.of("id"))
                .constraintName("FK_orders_detail_user_id_users")
                .sourceAttributeName("user")
                .build();
        orders.getRelationships().put(rel.getConstraintName(), rel);

        // Descriptor: @ManyToOne + @MapsId
        ManyToOne m2o = mock(ManyToOne.class);
        lenient().when(m2o.optional()).thenReturn(true);
        lenient().when(m2o.cascade()).thenReturn(new jakarta.persistence.CascadeType[0]);
        lenient().when(m2o.fetch()).thenReturn(jakarta.persistence.FetchType.EAGER);
        MapsId mapsId = mock(MapsId.class);
        when(mapsId.value()).thenReturn("");

        VariableElement diagElem = mock(VariableElement.class);
        AttributeDescriptor d = AttributeDescriptorFactory.withDiagnostic(
                AttributeDescriptorFactory.setOf("com.example.User", "user", m2o, mapsId),
                diagElem
        );

        // Context stubs
        when(context.getCachedDescriptors(ownerType)).thenReturn(List.of(d));
        when(context.findAllPrimaryKeyColumns(orders)).thenReturn(
                List.of(orders.findColumn("orders", "id"))
        );
        when(context.getMessager()).thenReturn(messager);

        // Run
        handler.processMapsIdAttributes(ownerType, orders);

        // Assert error + entity invalidated
        MessagerAssertions.assertErrorContains(messager,
                "requires FK to be on owner's primary table");
        assertFalse(orders.isValid());
    }

    @Test
    void mapsId_errors_whenNotOwningToOne() {
        // Entity (valid shell)
        EntityModel orders = EntityModelMother.javaEntityWithPkIdLong("com.example.Order", "orders");

        // Descriptor: OneToOne inverse side (mappedBy set) + @MapsId
        OneToOne o2oInverse = mock(OneToOne.class);
        when(o2oInverse.mappedBy()).thenReturn("parent"); // inverse side
        lenient().when(o2oInverse.cascade()).thenReturn(new jakarta.persistence.CascadeType[0]);
        lenient().when(o2oInverse.fetch()).thenReturn(jakarta.persistence.FetchType.EAGER);
        lenient().when(o2oInverse.optional()).thenReturn(true);

        MapsId mapsId = mock(MapsId.class);
        when(mapsId.value()).thenReturn("");

        VariableElement diagElem = mock(VariableElement.class);
        AttributeDescriptor d = AttributeDescriptorFactory.withDiagnostic(
                AttributeDescriptorFactory.setOf("com.example.Parent", "parent", o2oInverse, mapsId),
                diagElem
        );

        // Context stubs
        when(context.getCachedDescriptors(ownerType)).thenReturn(List.of(d));
        when(context.getMessager()).thenReturn(messager);

        // Run
        handler.processMapsIdAttributes(ownerType, orders);

        // Assert error + entity invalid
        MessagerAssertions.assertErrorContains(messager,
                "@MapsId can only be used on owning side ToOne relationships");
        assertFalse(orders.isValid());
    }
}
