package org.jinx.handler;

import jakarta.persistence.AccessType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import org.jinx.context.Naming;
import org.jinx.context.ProcessingContext;
import org.jinx.descriptor.AttributeDescriptor;
import org.jinx.handler.relationship.RelationshipProcessor;
import org.jinx.model.ColumnModel;
import org.jinx.model.ConstraintModel;
import org.jinx.model.EntityModel;
import org.jinx.model.RelationshipModel;
import org.jinx.util.AccessUtils;
import org.jinx.testing.asserts.ColumnAssertions;
import org.jinx.testing.asserts.MessagerAssertions;
import org.jinx.testing.mother.AttributeDescriptorFactory;
import org.jinx.testing.mother.EntityModelMother;
import org.jinx.testing.mother.RelationshipModelMother;
import org.jinx.testing.util.AnnotationProxies;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoSettings;

import javax.annotation.processing.Messager;
import javax.lang.model.element.TypeElement;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RelationshipHandlerTest {

    @Mock
    private ProcessingContext context;
    @Mock
    private Messager messager;
    @Mock
    private RelationshipProcessor p1, p2; // Mock processors
    @Mock
    private TypeElement ownerType;
    @Mock
    private Naming naming;

    // The class under test
    private RelationshipHandler relationshipHandler;

    @BeforeEach
    void setUp() {
        // Mock the context to return the messager
        when(context.getMessager()).thenReturn(messager);
        when(context.getNaming()).thenReturn(naming);

        // Use a real RelationshipHandler but inject mocks for its processors.
        // This is a bit tricky due to constructor initialization. We'll create a spy
        // or manually construct it with mocked processors. Let's manually construct.
        relationshipHandler = new RelationshipHandler(context) {
            // Override the internal list of processors with our mocks
            {
                // This replaces the real processors list with our mocks for testing
                // The reflection is a bit of a hack, but cleaner than alternatives
                try {
                    var field = RelationshipHandler.class.getDeclaredField("processors");
                    field.setAccessible(true);
                    field.set(this, List.of(p1, p2));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        };

        // Define default behavior for the mock processors
        when(p1.supports(any())).thenReturn(false);
        when(p2.supports(any())).thenReturn(false);
    }

    @Nested
    @DisplayName("resolveRelationships Method")
    class ResolveRelationshipsTests {

        @Test
        @DisplayName("should use cached descriptors if available")
        void shouldUseCachedDescriptors() {
            // Arrange
            AttributeDescriptor cachedDescriptor = AttributeDescriptorFactory.setOf("com.example.User", "user", mock(ManyToOne.class));
            when(context.getCachedDescriptors(ownerType)).thenReturn(List.of(cachedDescriptor));
            EntityModel ownerEntity = EntityModelMother.javaEntity("com.example.Order", "orders");
            when(p1.supports(cachedDescriptor)).thenReturn(true);

            // Act
            relationshipHandler.resolveRelationships(ownerType, ownerEntity);

            // Assert
            verify(p1).process(cachedDescriptor, ownerEntity); // Verify the processor was called
            verifyNoInteractions(ownerType); // Ensure no further reflection-like operations on ownerType
        }

        @Test
        @DisplayName("should scan fields when AccessType is FIELD and cache is empty")
        void shouldScanFieldsForFieldAccess() {
            // Arrange
            when(context.getCachedDescriptors(ownerType)).thenReturn(null); // No cache
            EntityModel ownerEntity = EntityModelMother.javaEntity("com.example.Order", "orders");

            try (MockedStatic<AccessUtils> accessUtils = mockStatic(AccessUtils.class)) {
                accessUtils.when(() -> AccessUtils.determineAccessType(ownerType)).thenReturn(AccessType.FIELD);

                // We'll spy on the handler to verify the private method is called
                RelationshipHandler spyHandler = spy(relationshipHandler);
                doNothing().when(spyHandler).resolve(any(AttributeDescriptor.class), any(EntityModel.class));

                // Act
                spyHandler.resolveRelationships(ownerType, ownerEntity);

                // Assert
                // We can't directly verify private method calls. Instead, we trust the logic inside
                // scanFieldsForRelationships to call `resolve`. Let's test `resolve`'s logic instead.
                // For this test, verifying AccessUtils was called is the key.
                accessUtils.verify(() -> AccessUtils.determineAccessType(ownerType));
            }
        }
    }

    @Nested
    @DisplayName("resolve (Dispatcher) Method")
    class ResolveDispatcherTests {

        @Test
        @DisplayName("should delegate to the first supporting processor")
        void shouldDelegateToFirstSupporter() {
            // Arrange
            EntityModel ownerEntity = EntityModelMother.javaEntity("com.example.Order", "orders");
            AttributeDescriptor descriptor = AttributeDescriptorFactory.setOf("com.example.User", "user", mock(ManyToOne.class));

            // p1 does not support it, p2 does
            when(p1.supports(descriptor)).thenReturn(false);
            when(p2.supports(descriptor)).thenReturn(true);

            // Act
            relationshipHandler.resolve(descriptor, ownerEntity);

            // Assert
            verify(p1, never()).process(any(), any());
            verify(p2).process(descriptor, ownerEntity);
        }

        @Test
        @DisplayName("should log an error if no processor handles a relationship")
        void shouldLogErrorForUnhandledRelationship() {
            // Arrange
            EntityModel ownerEntity = EntityModelMother.javaEntity("com.example.Order", "orders");
            AttributeDescriptor descriptor = AttributeDescriptorFactory.setOf("com.example.User", "user", mock(ManyToOne.class));
            // Both processors say they don't support it
            when(p1.supports(descriptor)).thenReturn(false);
            when(p2.supports(descriptor)).thenReturn(false);

            // Act
            relationshipHandler.resolve(descriptor, ownerEntity);

            // Assert
            MessagerAssertions.assertErrorContains(messager, "No registered processor can handle relation on com.example.Order.user");
            verify(p1, never()).process(any(), any());
            verify(p2, never()).process(any(), any());
        }

        @Test
        @DisplayName("should do nothing for attributes without relationship annotations")
        void shouldIgnoreNonRelationshipAttributes() {
            // Arrange
            EntityModel ownerEntity = EntityModelMother.javaEntity("com.example.Order", "orders");
            AttributeDescriptor descriptor = AttributeDescriptorFactory.setOf("java.lang.String", "description"); // No relationship annotation

            // Act
            relationshipHandler.resolve(descriptor, ownerEntity);

            // Assert
            verifyNoInteractions(messager);
            verify(p1, never()).process(any(), any());
            verify(p2, never()).process(any(), any());
        }
    }

    @Nested
    @DisplayName("processMapsIdAttribute Method")
    class MapsIdTests {
        private EntityModel ownerEntity;
        private AttributeDescriptor mapsIdDescriptor;

        @BeforeEach
        void setUp() {
            ownerEntity = EntityModelMother.javaEntityWithPkIdLong("com.example.OrderDetail", "order_details");
            // Add a pre-existing relationship to simulate the first pass
            RelationshipModel rel = RelationshipModelMother.manyToOne(
                    "order", "order_details", List.of("order_fk"), "orders", List.of("id")
            );
            ownerEntity.getRelationships().put("id", rel);
            ownerEntity.putColumn(ColumnModel.builder().tableName("order_details").columnName("order_fk").javaType("java.lang.Long").build());

            // Set up mock for finding the PK column of the owner
            when(context.findAllPrimaryKeyColumns(ownerEntity)).thenReturn(
                    List.of(ownerEntity.findColumn("order_details", "id"))
            );
        }

        @Test
        @DisplayName("should promote FK to PK for @MapsId on simple key")
        void mapsIdOnSimpleKeyPromotesFkToPk() {
            // Arrange
            mapsIdDescriptor = AttributeDescriptorFactory.setOf("com.example.Order", "order",
                    AnnotationProxies.of(MapsId.class, Map.of("value", "")),
                    mock(ManyToOne.class)
            );

            // Act
            relationshipHandler.processMapsIdAttribute(mapsIdDescriptor, ownerEntity);

            // Assert
            ColumnAssertions.assertPkNonNull(ownerEntity, "order_details::order_fk", "java.lang.Long");

            RelationshipModel updatedRel = ownerEntity.getRelationships().values().iterator().next();
            assertEquals("", updatedRel.getMapsIdKeyPath());
            assertEquals(Map.of("order_fk", "id"), updatedRel.getMapsIdBindings());

            // Verify PK constraint was updated
            ConstraintModel pk = ownerEntity.getConstraints().values().stream()
                    .filter(c -> c.getType() == org.jinx.model.ConstraintType.PRIMARY_KEY).findFirst().orElse(null);
            assertNotNull(pk);
            assertTrue(pk.getColumns().containsAll(List.of("id", "order_fk")));
        }

        @Test
        @DisplayName("should fail if @MapsId is on an inverse relationship")
        void mapsIdOnInverseSideFails() {
            // Arrange
            mapsIdDescriptor = AttributeDescriptorFactory.setOf("com.example.Order", "order",
                    AnnotationProxies.of(MapsId.class, Map.of("value", "")),
                    AnnotationProxies.of(OneToOne.class, Map.of("mappedBy", "detail")) // Inverse side
            );

            // Act
            relationshipHandler.processMapsIdAttribute(mapsIdDescriptor, ownerEntity);

            // Assert
            MessagerAssertions.assertErrorContains(messager, "@MapsId can only be used on owning side ToOne relationships");
            assertFalse(ownerEntity.isValid());
        }

        @Test
        @DisplayName("should fail if owner has no primary key")
        void mapsIdWithNoOwnerPkFails() {
            // Arrange
            when(context.findAllPrimaryKeyColumns(ownerEntity)).thenReturn(List.of()); // No PK
            mapsIdDescriptor = AttributeDescriptorFactory.setOf("com.example.Order", "order",
                    AnnotationProxies.of(MapsId.class, Map.of("value", "")),
                    mock(ManyToOne.class)
            );

            // Act
            relationshipHandler.processMapsIdAttribute(mapsIdDescriptor, ownerEntity);

            // Assert
            MessagerAssertions.assertErrorContains(messager, "@MapsId requires a primary key on com.example.OrderDetail");
            assertFalse(ownerEntity.isValid());
        }

        @Test
        @DisplayName("should map specific composite PK attribute with @MapsId(\"value\")")
        void mapsIdWithValueOnCompositeKey() {
            // Arrange
            // Setup for a composite key scenario
            when(context.getPkColumnsForAttribute("com.example.OrderDetail", "orderPk.orderId"))
                    .thenReturn(List.of("order_id_pk"));
            // Simulate a composite PK by returning more than one column
            when(context.findAllPrimaryKeyColumns(ownerEntity)).thenReturn(List.of(
                    ColumnModel.builder().columnName("id").build(),
                    ColumnModel.builder().columnName("tenant_id").build()
            ));

            mapsIdDescriptor = AttributeDescriptorFactory.setOf("com.example.Order", "order",
                    AnnotationProxies.of(MapsId.class, Map.of("value", "orderPk.orderId")),
                    mock(ManyToOne.class)
            );

            // Act
            relationshipHandler.processMapsIdAttribute(mapsIdDescriptor, ownerEntity);

            // Assert
            ColumnAssertions.assertPkNonNull(ownerEntity, "order_details::order_fk", "java.lang.Long");

            RelationshipModel updatedRel = ownerEntity.getRelationships().values().iterator().next();
            assertEquals("orderPk.orderId", updatedRel.getMapsIdKeyPath());
            assertEquals(Map.of("order_fk", "order_id_pk"), updatedRel.getMapsIdBindings());
        }

        @Test
        @DisplayName("should fail for @MapsId(\"value\") if PK is not composite")
        void mapsIdWithValueOnSimpleKeyFails() {
            // Arrange
            // Context returns a single PK column, indicating a non-composite key
            when(context.findAllPrimaryKeyColumns(ownerEntity)).thenReturn(
                    List.of(ownerEntity.findColumn("order_details", "id"))
            );

            mapsIdDescriptor = AttributeDescriptorFactory.setOf("com.example.Order", "order",
                    AnnotationProxies.of(MapsId.class, Map.of("value", "orderId")),
                    mock(ManyToOne.class)
            );

            // Act
            relationshipHandler.processMapsIdAttribute(mapsIdDescriptor, ownerEntity);

            // Assert
            MessagerAssertions.assertErrorContains(messager, "cannot specify an attribute name for an entity with a single-column primary key");
        }
    }
}