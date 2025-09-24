package org.jinx.handler;

import jakarta.persistence.*;
import org.jinx.context.ProcessingContext;
import org.jinx.descriptor.AttributeDescriptor;
import org.jinx.model.*;
import org.jinx.naming.Naming;
import org.jinx.testing.asserts.ColumnAssertions;
import org.jinx.testing.asserts.MessagerAssertions;
import org.jinx.testing.asserts.RelationshipAssertions;
import org.jinx.testing.mother.EntityModelMother;
import org.jinx.testing.util.AnnotationMocks;
import org.jinx.testing.util.AnnotationProxies;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.annotation.processing.Messager;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmbeddedHandlerTest {

    @Mock private ProcessingContext context;
    @Mock private ColumnHandler columnHandler;
    @Mock private RelationshipHandler relationshipHandler;
    @Mock private Types typeUtils;
    @Mock private Elements elementUtils;
    @Mock private Messager messager;
    @Mock private SchemaModel schemaModel;
    @Mock private Naming naming;
    @Mock private org.jinx.descriptor.AttributeDescriptorFactory mockDescriptorFactory;

    private EmbeddedHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        // Mock context methods
        lenient().when(context.getTypeUtils()).thenReturn(typeUtils);
        lenient().when(context.getElementUtils()).thenReturn(elementUtils);
        lenient().when(context.getMessager()).thenReturn(messager);
        lenient().when(context.getSchemaModel()).thenReturn(schemaModel);
        lenient().when(context.getNaming()).thenReturn(naming);

        // Instantiate the handler
        handler = new EmbeddedHandler(context, columnHandler, relationshipHandler);

        // Use reflection to inject our mock AttributeDescriptorFactory,
        // which is crucial for isolating the EmbeddedHandler's logic.
        Field factoryField = EmbeddedHandler.class.getDeclaredField("descriptorFactory");
        factoryField.setAccessible(true);
        factoryField.set(handler, mockDescriptorFactory);

        // Default behavior for ColumnHandler: create a basic column model.
        // Tests can override this if needed.
        lenient().when(columnHandler.createFromAttribute(any(), any(), any())).thenAnswer(inv -> {
            AttributeDescriptor desc = inv.getArgument(0);
            EntityModel owner = inv.getArgument(1);
            Column leafCol = desc.getAnnotation(Column.class);
            String colName = (leafCol != null && !leafCol.name().isEmpty()) ? leafCol.name() : desc.name();
            return ColumnModel.builder()
                    .columnName(colName)
                    .tableName(owner.getTableName())
                    .javaType(desc.type().toString())
                    .build();
        });
    }

    // A helper to create a mock AttributeDescriptor for testing purposes
    private AttributeDescriptor mockAttribute(String name, String typeFqcn, Annotation... anns) {
        AttributeDescriptor ad = mock(AttributeDescriptor.class, name);
        lenient().when(ad.name()).thenReturn(name);

        TypeMirror type = mock(TypeMirror.class, name + "Type");
        lenient().when(type.toString()).thenReturn(typeFqcn);
        lenient().when(ad.type()).thenReturn(type);

        lenient().when(ad.getAnnotation(any())).thenAnswer(inv -> {
            Class<? extends Annotation> annClass = inv.getArgument(0);
            return Arrays.stream(anns).filter(annClass::isInstance).findFirst().orElse(null);
        });
        lenient().when(ad.hasAnnotation((Class<? extends Annotation>) any())).thenAnswer(inv -> {
            Class<? extends Annotation> annClass = inv.getArgument(0);
            return Arrays.stream(anns).anyMatch(annClass::isInstance);
        });

        // Link type mirror to a mock TypeElement for relationship processing
        if (Arrays.stream(anns).anyMatch(a -> a instanceof ManyToOne || a instanceof OneToOne)) {
            TypeElement targetElement = mock(TypeElement.class);
            Name fqcn = mock(Name.class);
            lenient().when(fqcn.toString()).thenReturn(typeFqcn);
            lenient().when(targetElement.getQualifiedName()).thenReturn(fqcn);

            DeclaredType declaredType = mock(DeclaredType.class);
            lenient().when(declaredType.asElement()).thenReturn(targetElement);
            lenient().when(ad.type()).thenReturn(declaredType);
        }

        return ad;
    }

    // A helper to set up a mock embeddable type
    private DeclaredType mockEmbeddableType(String fqcn, List<AttributeDescriptor> descriptors) {
        TypeElement typeElement = mock(TypeElement.class);
        lenient().when(typeElement.getAnnotation(Embeddable.class)).thenReturn(mock(Embeddable.class));
        lenient().when(typeElement.getQualifiedName()).thenReturn(mock(javax.lang.model.element.Name.class));
        lenient().when(typeElement.getQualifiedName().toString()).thenReturn(fqcn);

        DeclaredType declaredType = mock(DeclaredType.class);
        lenient().when(declaredType.asElement()).thenReturn(typeElement);

        // Configure the mock factory to return our desired descriptors for this type
        when(mockDescriptorFactory.createDescriptors(typeElement)).thenReturn(descriptors);
        return declaredType;
    }

    // =================================================================
    // ## Basic @Embedded Tests
    // =================================================================

    @Test
    void processEmbedded_Simple_AddsPrefixedColumns() {
        // Arrange
        EntityModel owner = EntityModelMother.javaEntityWithPkIdLong("com.ex.User.java", "users");
        AttributeDescriptor streetAttr = mockAttribute("street", "java.lang.String");
        AttributeDescriptor cityAttr = mockAttribute("city", "java.lang.String");
        DeclaredType addressType = mockEmbeddableType("com.ex.Address", List.of(streetAttr, cityAttr));

        AttributeDescriptor embedAttr = mock(AttributeDescriptor.class);
        when(embedAttr.type()).thenReturn(addressType);
        when(embedAttr.name()).thenReturn("location");

        // Act
        handler.processEmbedded(embedAttr, owner, new HashSet<>());

        // Assert
        assertEquals(3, owner.getColumns().size()); // id + street + city
        ColumnAssertions.assertNonPkWithType(owner, "users", "location_street", "java.lang.String");
        ColumnAssertions.assertNonPkWithType(owner, "users", "location_city", "java.lang.String");
    }

    @Test
    void processEmbedded_Nested_AddsCumulativePrefixes() {
        // Arrange
        EntityModel owner = EntityModelMother.javaEntityWithPkIdLong("com.ex.User.java", "users");

        // Innermost embeddable: Coordinates
        AttributeDescriptor latAttr = mockAttribute("lat", "double");
        AttributeDescriptor lonAttr = mockAttribute("lon", "double");
        DeclaredType coordsType = mockEmbeddableType("com.ex.Coordinates", List.of(latAttr, lonAttr));

        // Middle embeddable: Location, which contains Coordinates
        AttributeDescriptor coordsAttr = mockAttribute("coords", "com.ex.Coordinates", mock(Embedded.class));
        when(coordsAttr.type()).thenReturn(coordsType);
        DeclaredType locationType = mockEmbeddableType("com.ex.Location", List.of(coordsAttr));

        // Top-level @Embedded attribute in User.java
        AttributeDescriptor embedAttr = mock(AttributeDescriptor.class);
        when(embedAttr.type()).thenReturn(locationType);
        when(embedAttr.name()).thenReturn("home");

        // Act
        handler.processEmbedded(embedAttr, owner, new HashSet<>());

        // Assert
        assertEquals(3, owner.getColumns().size()); // id + lat + lon
        ColumnAssertions.assertNonPkWithType(owner, "users", "home_coords_lat", "double");
        ColumnAssertions.assertNonPkWithType(owner, "users", "home_coords_lon", "double");
    }

    @Test
    void processEmbedded_ColumnNamePrecedence_PrefersOverridesAndExplicitNames() {
        // Arrange
        EntityModel owner = EntityModelMother.javaEntityWithPkIdLong("com.ex.User.java", "users");

        // field1: default name, should be prefixed
        AttributeDescriptor field1Attr = mockAttribute("field1", "java.lang.String");
        // field2: has explicit @Column(name), should NOT be prefixed
        Column field2ColAnn = AnnotationProxies.of(Column.class, Map.of("name", "FIELD_2_EXPLICIT"));
        AttributeDescriptor field2Attr = mockAttribute("field2", "java.lang.String", field2ColAnn);

        DeclaredType embeddableType = mockEmbeddableType("com.ex.Embed", List.of(field1Attr, field2Attr));

        // Top-level attribute with an override for 'field1'
        AttributeOverride override = AnnotationProxies.of(AttributeOverride.class, Map.of(
                "name", "field1",
                "column", AnnotationProxies.of(Column.class, Map.of("name", "FIELD_1_OVERRIDE"))
        ));
        AttributeDescriptor embedAttr = mock(AttributeDescriptor.class);
        when(embedAttr.getAnnotation(AttributeOverride.class)).thenReturn(override);
        when(embedAttr.getAnnotation(AttributeOverrides.class)).thenReturn(null);
        when(embedAttr.getAnnotation(AssociationOverride.class)).thenReturn(null);
        when(embedAttr.getAnnotation(AssociationOverrides.class)).thenReturn(null);
        when(embedAttr.type()).thenReturn(embeddableType);
        when(embedAttr.name()).thenReturn("data");


        // Act
        handler.processEmbedded(embedAttr, owner, new HashSet<>());

        // Assert
        assertEquals(3, owner.getColumns().size());
        // field1 was overridden, so it uses the override name without a prefix.
        ColumnAssertions.assertNonPkWithType(owner, "users", "FIELD_1_OVERRIDE", "java.lang.String");
        // field2 had an explicit name, so it uses that name without a prefix.
        ColumnAssertions.assertNonPkWithType(owner, "users", "FIELD_2_EXPLICIT", "java.lang.String");
    }

    // =================================================================
    // ## @EmbeddedId Tests
    // =================================================================

    @Test
    void processEmbeddedId_CreatesPkColumnsAndRegistersThem() {
        // Arrange
        EntityModel owner = EntityModelMother.javaEntity("com.ex.Order", "orders");
        AttributeDescriptor customerIdAttr = mockAttribute("customerId", "long");
        AttributeDescriptor orderNumAttr = mockAttribute("orderNum", "java.lang.String");
        DeclaredType orderIdType = mockEmbeddableType("com.ex.OrderId", List.of(customerIdAttr, orderNumAttr));

        AttributeDescriptor idAttr = mock(AttributeDescriptor.class);
        when(idAttr.type()).thenReturn(orderIdType);
        when(idAttr.name()).thenReturn("pk");

        // Act
        handler.processEmbeddedId(idAttr, owner, new HashSet<>());

        // Assert - Columns are primary keys and not nullable
        assertEquals(2, owner.getColumns().size());
        ColumnAssertions.assertPkNonNull(owner, "orders", "pk_customerId", "long");
        ColumnAssertions.assertPkNonNull(owner, "orders", "pk_orderNum", "java.lang.String");

        // Assert - Context registration for @MapsId
        verify(context).registerPkAttributeColumns("com.ex.Order", "pk.customerId", List.of("pk_customerId"));
        verify(context).registerPkAttributeColumns("com.ex.Order", "pk.orderNum", List.of("pk_orderNum"));
    }

    // =================================================================
    // ## Embedded Relationship Tests
    // =================================================================

    @Test
    void processEmbeddedRelationship_SimpleManyToOne_CreatesForeignKey() {
        // Arrange
        EntityModel owner = EntityModelMother.javaEntityWithPkIdLong("com.ex.Employee", "employees");

        EntityModel countryEntity = EntityModelMother.javaEntity("com.ex.Country", "countries");
        ColumnModel countryPk = EntityModelMother.pkColumn("countries", "code", "java.lang.String");
        countryEntity.putColumn(countryPk);
        when(schemaModel.getEntities()).thenReturn(Map.of("com.ex.Country", countryEntity));
        when(context.findAllPrimaryKeyColumns(countryEntity)).thenReturn(List.of(countryPk));

        ManyToOne m2oAnn = AnnotationProxies.of(ManyToOne.class, Map.of("country", void.class));
        AttributeDescriptor countryRelAttr = mockAttribute("country", "com.ex.Country", m2oAnn);
        DeclaredType addressType = mockEmbeddableType("com.ex.Address", List.of(countryRelAttr));

        AttributeDescriptor embedAttr = mock(AttributeDescriptor.class);
        when(embedAttr.type()).thenReturn(addressType);
        when(embedAttr.name()).thenReturn("address");

        when(naming.fkName(any(), any(), any(), any())).thenReturn("FK_emp_country");

        // Act
        handler.processEmbedded(embedAttr, owner, new HashSet<>());

        // Assert
        // A foreign key column is created with the default naming convention: prefix_attr_pkcol
        ColumnAssertions.assertNonPkWithType(owner, "employees", "address_country_code", "java.lang.String");

        // A relationship model is created
        RelationshipAssertions.assertFk(owner, "FK_emp_country",
                "employees", List.of("address_country_code"),
                "countries", List.of("code"),
                RelationshipType.MANY_TO_ONE);
    }

    @Test
    void processEmbeddedRelationship_AssociationOverride_UsesOverriddenJoinColumn() {
        // Arrange
        EntityModel owner = EntityModelMother.javaEntityWithPkIdLong("com.ex.Employee", "employees");
        EntityModel countryEntity = EntityModelMother.javaEntityWithPkIdLong("com.ex.Country", "countries");
        when(schemaModel.getEntities()).thenReturn(Map.of("com.ex.Country", countryEntity));
        when(context.findAllPrimaryKeyColumns(countryEntity)).thenReturn(List.of(
                countryEntity.findColumn("countries", "id")
        ));

        ManyToOne m2oAnn = AnnotationProxies.of(ManyToOne.class, Map.of("targetEntity", void.class));
        AttributeDescriptor countryRelAttr = mockAttribute("country", "com.ex.Country", m2oAnn);
        DeclaredType addressType = mockEmbeddableType("com.ex.Address", List.of(countryRelAttr));

        AssociationOverride override = AnnotationProxies.of(AssociationOverride.class, Map.of(
                "name", "country",
                "joinColumns", new JoinColumn[]{
                        AnnotationMocks.joinColumn("COUNTRY_ID_FK", "id")
                }
        ));
        AttributeDescriptor embedAttr = mock(AttributeDescriptor.class);
        when(embedAttr.getAnnotation(AssociationOverride.class)).thenReturn(override);
        when(embedAttr.getAnnotation(AssociationOverrides.class)).thenReturn(null);
        when(embedAttr.getAnnotation(AttributeOverride.class)).thenReturn(null);
        when(embedAttr.getAnnotation(AttributeOverrides.class)).thenReturn(null);
        when(embedAttr.type()).thenReturn(addressType);
        when(embedAttr.name()).thenReturn("address");

        when(naming.fkName(any(), any(), any(), any())).thenReturn("FK_emp_country_override");

        // Act
        handler.processEmbedded(embedAttr, owner, new HashSet<>());

        // Assert
        // 1. 기본 생성 이름(address_country_id)을 가진 컬럼은 없어야 함을 확인
        assertNull(owner.findColumn("employees", "address_country_id"));

        // 2. @AssociationOverride에 명시된 이름("COUNTRY_ID_FK")을 가진 컬럼이 생성되었는지 확인
        ColumnAssertions.assertNonPkWithType(owner, "employees", "COUNTRY_ID_FK", "java.lang.Long");

        // 3. Relationship 모델이 올바른 컬럼 이름("COUNTRY_ID_FK")으로 생성되었는지 확인
        RelationshipAssertions.assertFk(owner, "FK_emp_country_override",
                "employees", List.of("COUNTRY_ID_FK"), // <--- 수정된 부분!
                "countries", List.of("id"),
                RelationshipType.MANY_TO_ONE);
    }

    @Test
    void processEmbeddedRelationship_CompositePkWithoutJoinColumns_LogsError() {
        // Arrange
        EntityModel owner = EntityModelMother.javaEntityWithPkIdLong("com.ex.User.java", "users");

        EntityModel compositePkEntity = EntityModelMother.javaEntity("com.ex.Composite", "composites");
        ColumnModel pk1 = EntityModelMother.pkColumn("composites", "pk1", "int");
        ColumnModel pk2 = EntityModelMother.pkColumn("composites", "pk2", "int");
        compositePkEntity.putColumn(pk1);
        compositePkEntity.putColumn(pk2);
        when(schemaModel.getEntities()).thenReturn(Map.of("com.ex.Composite", compositePkEntity));
        when(context.findAllPrimaryKeyColumns(compositePkEntity)).thenReturn(List.of(pk1, pk2));

        ManyToOne m2oAnn = AnnotationProxies.of(ManyToOne.class, Map.of("targetEntity", void.class));
        AttributeDescriptor relAttr = mockAttribute("comp", "com.ex.Composite", m2oAnn);
        DeclaredType embeddableType = mockEmbeddableType("com.ex.Embed", List.of(relAttr));

        AttributeDescriptor embedAttr = mock(AttributeDescriptor.class);
        when(embedAttr.type()).thenReturn(embeddableType);
        lenient().when(embedAttr.name()).thenReturn("data");

        // Act
        handler.processEmbedded(embedAttr, owner, new HashSet<>());

        // Assert
        MessagerAssertions.assertErrorContains(messager, "Composite primary key on com.ex.Composite requires explicit @JoinColumns");
        // No columns or relationships should be added
        assertEquals(1, owner.getColumns().size()); // Only the original PK
        assertTrue(owner.getRelationships().isEmpty());
    }

    // =================================================================
    // ## Record @Embeddable Tests
    // =================================================================

    @Test
    void processEmbedded_RecordEmbeddable_AddsPrefixedColumns() {
        // Arrange
        EntityModel owner = EntityModelMother.javaEntityWithPkIdLong("com.ex.Employee", "employees");

        // Mock record components for Address record
        AttributeDescriptor streetAttr = mockAttribute("street", "java.lang.String");
        AttributeDescriptor cityAttr = mockAttribute("city", "java.lang.String");
        AttributeDescriptor zipCodeAttr = mockAttribute("zipCode", "java.lang.String");
        DeclaredType addressRecordType = mockEmbeddableType("entities.Address", List.of(streetAttr, cityAttr, zipCodeAttr));

        AttributeDescriptor embedAttr = mock(AttributeDescriptor.class);
        when(embedAttr.type()).thenReturn(addressRecordType);
        when(embedAttr.name()).thenReturn("address");

        // Act
        handler.processEmbedded(embedAttr, owner, new HashSet<>());

        // Assert
        assertEquals(4, owner.getColumns().size()); // id + street + city + zipCode
        ColumnAssertions.assertNonPkWithType(owner, "employees", "address_street", "java.lang.String");
        ColumnAssertions.assertNonPkWithType(owner, "employees", "address_city", "java.lang.String");
        ColumnAssertions.assertNonPkWithType(owner, "employees", "address_zipCode", "java.lang.String");
    }

    @Test
    void processEmbedded_RecordWithAttributeOverrides_AppliesOverrides() {
        // Arrange
        EntityModel owner = EntityModelMother.javaEntityWithPkIdLong("com.ex.Employee", "employees");

        AttributeDescriptor streetAttr = mockAttribute("street", "java.lang.String");
        AttributeDescriptor cityAttr = mockAttribute("city", "java.lang.String");
        DeclaredType addressRecordType = mockEmbeddableType("entities.Address", List.of(streetAttr, cityAttr));

        // Create AttributeOverride for street field
        AttributeOverrides overrides = AnnotationProxies.of(AttributeOverrides.class, Map.of(
                "value", new AttributeOverride[]{
                        AnnotationProxies.of(AttributeOverride.class, Map.of(
                                "name", "street",
                                "column", AnnotationProxies.of(Column.class, Map.of("name", "home_street"))
                        )),
                        AnnotationProxies.of(AttributeOverride.class, Map.of(
                                "name", "city",
                                "column", AnnotationProxies.of(Column.class, Map.of("name", "home_city"))
                        ))
                }
        ));

        AttributeDescriptor embedAttr = mock(AttributeDescriptor.class);
        when(embedAttr.getAnnotation(AttributeOverrides.class)).thenReturn(overrides);
        when(embedAttr.getAnnotation(AttributeOverride.class)).thenReturn(null);
        when(embedAttr.getAnnotation(AssociationOverride.class)).thenReturn(null);
        when(embedAttr.getAnnotation(AssociationOverrides.class)).thenReturn(null);
        when(embedAttr.type()).thenReturn(addressRecordType);
        when(embedAttr.name()).thenReturn("homeAddress");

        // Act
        handler.processEmbedded(embedAttr, owner, new HashSet<>());

        // Assert
        assertEquals(3, owner.getColumns().size()); // id + street + city
        ColumnAssertions.assertNonPkWithType(owner, "employees", "home_street", "java.lang.String");
        ColumnAssertions.assertNonPkWithType(owner, "employees", "home_city", "java.lang.String");
    }

    @Test
    void processEmbeddedId_RecordEmbeddable_CreatesPkColumns() {
        // Arrange
        EntityModel owner = EntityModelMother.javaEntity("com.ex.OrderRecord", "order_records");

        AttributeDescriptor customerIdAttr = mockAttribute("customerId", "long");
        AttributeDescriptor orderNumberAttr = mockAttribute("orderNumber", "java.lang.String");
        DeclaredType orderIdRecordType = mockEmbeddableType("entities.OrderIdRecord", List.of(customerIdAttr, orderNumberAttr));

        AttributeDescriptor idAttr = mock(AttributeDescriptor.class);
        when(idAttr.type()).thenReturn(orderIdRecordType);
        when(idAttr.name()).thenReturn("id");

        // Act
        handler.processEmbeddedId(idAttr, owner, new HashSet<>());

        // Assert - Columns are primary keys and not nullable
        assertEquals(2, owner.getColumns().size());
        ColumnAssertions.assertPkNonNull(owner, "order_records", "id_customerId", "long");
        ColumnAssertions.assertPkNonNull(owner, "order_records", "id_orderNumber", "java.lang.String");

        // Assert - Context registration for @MapsId
        verify(context).registerPkAttributeColumns("com.ex.OrderRecord", "id.customerId", List.of("id_customerId"));
        verify(context).registerPkAttributeColumns("com.ex.OrderRecord", "id.orderNumber", List.of("id_orderNumber"));
    }
}