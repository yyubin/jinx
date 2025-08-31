package org.jinx.handler;

import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import org.jinx.context.ProcessingContext;
import org.jinx.descriptor.AttributeDescriptor;
import org.jinx.model.ColumnModel;
import org.jinx.model.ConstraintModel;
import org.jinx.model.ConstraintType;
import org.jinx.model.EntityModel;
import org.jinx.model.SchemaModel;
import org.jinx.testing.asserts.ColumnAssertions;
import org.jinx.testing.asserts.MessagerAssertions;
import org.jinx.testing.asserts.RelationshipAssertions;
import org.jinx.testing.mother.AttributeDescriptorFactory;
import org.jinx.testing.mother.EntityModelMother;
import org.jinx.testing.util.NamingTestUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.annotation.processing.Messager;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.Elements;
import java.util.*;

import static org.jinx.testing.asserts.RelationshipAssertions.assertFk;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RelationshipHandlerDispatchTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    ProcessingContext context;

    @Mock
    Messager messager;

    RelationshipHandler handler;

    // 체인 끊기용 로컬 mock
    SchemaModel schemaModel;
    Elements elements;

    @BeforeEach
    void setUp() {
        handler = new RelationshipHandler(context);
        when(context.getMessager()).thenReturn(messager);

        // naming stubs
        when(context.getNaming().foreignKeyColumnName(anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(0) + "_" + inv.getArgument(1));
        when(context.getNaming().fkName(anyString(), anyList(), anyString(), anyList()))
                .thenAnswer(inv -> NamingTestUtil.fk(
                        inv.getArgument(0),  // table
                        inv.getArgument(1),  // cols
                        inv.getArgument(2),  // refTable
                        inv.getArgument(3)   // refCols
                ));
        when(context.getNaming().uqName(anyString(), anyList()))
                .thenAnswer(inv -> "UQ_" + inv.getArgument(0) + "_" + String.join("_", (List<String>) inv.getArgument(1)));
        when(context.getNaming().pkName(anyString(), anyList()))
                .thenAnswer(inv -> "PK_" + inv.getArgument(0));

        // 체인 분리: SchemaModel / Elements
        schemaModel = mock(SchemaModel.class, Answers.RETURNS_DEEP_STUBS);
        when(context.getSchemaModel()).thenReturn(schemaModel);

        elements = mock(Elements.class, Answers.RETURNS_DEEP_STUBS);
        when(context.getElementUtils()).thenReturn(elements);
    }

    private static TypeElement typeElementWithFqcn(String fqcn) {
        TypeElement te = mock(TypeElement.class);
        Name name = mock(Name.class);
        when(name.toString()).thenReturn(fqcn);
        when(te.getQualifiedName()).thenReturn(name);
        return te;
    }

    @Test
    void resolve_manyToOne_addsFkAndColumn_onOwnerTable() {
        // 참조 엔티티(User)
        EntityModel user = EntityModelMother.javaEntityWithPkIdLong("com.example.User", "users");

        Map<String, EntityModel> schema = new HashMap<>();
        schema.put("com.example.User", user);
        when(schemaModel.getEntities()).thenReturn(schema);
        when(context.findAllPrimaryKeyColumns(user))
                .thenReturn(new ArrayList<>(List.of(user.findColumn("users", "id"))));

        // elements 분리 모킹
        when(elements.getTypeElement("com.example.User"))
                .thenReturn(typeElementWithFqcn("com.example.User"));

        // 오너 엔티티
        EntityModel orders = EntityModelMother.javaEntity("com.example.Order", "orders");

        // 디스크립터(@ManyToOne user)
        ManyToOne m2o = mock(ManyToOne.class);
        when(m2o.optional()).thenReturn(true);
        when(m2o.cascade()).thenReturn(new jakarta.persistence.CascadeType[0]);
        when(m2o.fetch()).thenReturn(jakarta.persistence.FetchType.EAGER);

        VariableElement diag = mock(VariableElement.class);
        AttributeDescriptor d = AttributeDescriptorFactory.withDiagnostic(
                AttributeDescriptorFactory.setOf("com.example.User", "user", m2o),
                diag
        );

        handler.resolve(d, orders);

        String fkName = NamingTestUtil.fk("orders", List.of("user_id"), "users", List.of("id"));
        assertFk(orders, fkName, "orders", List.of("user_id"), "users", List.of("id"),
                org.jinx.model.RelationshipType.MANY_TO_ONE);

        ColumnAssertions.assertNonPkWithType(orders, "orders.user_id", "java.lang.Long");
        assertTrue(orders.findColumn("orders", "user_id").isNullable(), "optional=true → nullable=true");
    }

    @Test
    void resolve_oneToOne_owning_addsUnique_whenSingleFk_andNoMapsId() {
        EntityModel profile = EntityModelMother.javaEntityWithPkIdLong("com.example.Profile", "profiles");

        Map<String, EntityModel> schema = new HashMap<>();
        schema.put("com.example.Profile", profile);
        when(schemaModel.getEntities()).thenReturn(schema);
        when(context.findAllPrimaryKeyColumns(profile))
                .thenReturn(new ArrayList<>(List.of(profile.findColumn("profiles", "id"))));

        when(elements.getTypeElement("com.example.Profile"))
                .thenReturn(typeElementWithFqcn("com.example.Profile"));

        EntityModel users = EntityModelMother.javaEntity("com.example.User", "users");

        OneToOne o2o = mock(OneToOne.class);
        when(o2o.mappedBy()).thenReturn("");
        when(o2o.optional()).thenReturn(true);
        when(o2o.cascade()).thenReturn(new jakarta.persistence.CascadeType[0]);
        when(o2o.fetch()).thenReturn(jakarta.persistence.FetchType.EAGER);

        VariableElement diag = mock(VariableElement.class);
        AttributeDescriptor d = AttributeDescriptorFactory.withDiagnostic(
                AttributeDescriptorFactory.setOf("com.example.Profile", "profile", o2o),
                diag
        );

        // Explicitly state that no @JoinColumn annotations are present for this test case
        when(d.getAnnotation(JoinColumn.class)).thenReturn(null);
        when(d.getAnnotation(JoinColumns.class)).thenReturn(null);

        handler.resolve(d, users);

        String fkName = NamingTestUtil.fk("users", List.of("profile_id"), "profiles", List.of("id"));
        RelationshipAssertions.assertFk(users, fkName, "users", List.of("profile_id"), "profiles", List.of("id"),
                org.jinx.model.RelationshipType.ONE_TO_ONE);

        String uqName = "UQ_users_profile_id";
        ConstraintModel uq = users.getConstraints().get(uqName);
        assertNotNull(uq, "expected unique constraint for 1:1");
        assertEquals(ConstraintType.UNIQUE, uq.getType());
        assertEquals(List.of("profile_id"), uq.getColumns());
        assertEquals("users", uq.getTableName());
    }

    @Test
    void resolveRelationships_usesCachedDescriptorsPath() {
        EntityModel user = EntityModelMother.javaEntityWithPkIdLong("com.example.User", "users");

        Map<String, EntityModel> schema = new HashMap<>();
        schema.put("com.example.User", user);
        when(schemaModel.getEntities()).thenReturn(schema);
        when(context.findAllPrimaryKeyColumns(user))
                .thenReturn(new ArrayList<>(List.of(user.findColumn("users", "id"))));

        when(elements.getTypeElement("com.example.User"))
                .thenReturn(typeElementWithFqcn("com.example.User"));

        EntityModel orders = EntityModelMother.javaEntity("com.example.Order", "orders");

        ManyToOne m2o = mock(ManyToOne.class);
        when(m2o.optional()).thenReturn(true);
        when(m2o.cascade()).thenReturn(new jakarta.persistence.CascadeType[0]);
        when(m2o.fetch()).thenReturn(jakarta.persistence.FetchType.EAGER);

        AttributeDescriptor d = AttributeDescriptorFactory.setOf("com.example.User", "user", m2o);
        when(context.getCachedDescriptors(any())).thenReturn(List.of(d));

        handler.resolveRelationships(typeElementWithFqcn("com.example.Order"), orders);

        String fkName = NamingTestUtil.fk("orders", List.of("user_id"), "users", List.of("id"));
        assertNotNull(orders.getRelationships().get(fkName),
                "expected relationship created via cached-descriptor path");
    }

    @Test
    void resolve_errors_whenReferencedPkIsComposite_andJoinColumnsMissing() {
        // composite PK (id_a, id_b)
        EntityModel product = EntityModelMother.javaEntity("com.example.Product", "products");
        ColumnModel ida = ColumnModel.builder().tableName("products").columnName("id_a")
                .javaType("java.lang.Long").isPrimaryKey(true).isNullable(false).build();
        ColumnModel idb = ColumnModel.builder().tableName("products").columnName("id_b")
                .javaType("java.lang.Long").isPrimaryKey(true).isNullable(false).build();
        product.putColumn(ida);
        product.putColumn(idb);

        Map<String, EntityModel> schema = new HashMap<>();
        schema.put("com.example.Product", product);
        when(schemaModel.getEntities()).thenReturn(schema);
        when(context.findAllPrimaryKeyColumns(product))
                .thenReturn(new ArrayList<>(List.of(ida, idb)));

        when(elements.getTypeElement("com.example.Product"))
                .thenReturn(typeElementWithFqcn("com.example.Product"));

        when(context.getMessager()).thenReturn(messager);

        EntityModel orders = EntityModelMother.javaEntity("com.example.Order", "orders");

        ManyToOne m2o = mock(ManyToOne.class);
        when(m2o.optional()).thenReturn(true);
        when(m2o.cascade()).thenReturn(new jakarta.persistence.CascadeType[0]);
        when(m2o.fetch()).thenReturn(jakarta.persistence.FetchType.EAGER);

        VariableElement diag = mock(VariableElement.class);
        AttributeDescriptor d = AttributeDescriptorFactory.withDiagnostic(
                AttributeDescriptorFactory.setOf("com.example.Product", "product", m2o),
                diag
        );

        handler.resolve(d, orders);

        // 오류 메시지 및 invalid 처리 확인
        MessagerAssertions.assertErrorContains(messager,
                "Composite primary key on com.example.Product requires explicit @JoinColumns");
        assertFalse(orders.isValid(), "owner entity should be invalid after error");
    }
}
