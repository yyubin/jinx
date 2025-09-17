package org.jinx.handler.relationship;

import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import org.jinx.context.ProcessingContext;
import org.jinx.descriptor.AttributeDescriptor;
import org.jinx.model.EntityModel;
import org.jinx.model.SchemaModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InverseRelationshipProcessorTest {

    ProcessingContext context;
    RelationshipSupport support;
    Messager messager;
    Elements elements;

    InverseRelationshipProcessor processor;

    AttributeDescriptor attr;
    EntityModel owner;
    Map<String, EntityModel> entities;

    @BeforeEach
    void setUp() {
        context = mock(ProcessingContext.class);
        support = mock(RelationshipSupport.class);
        messager = mock(Messager.class);
        elements = mock(Elements.class);
        when(context.getMessager()).thenReturn(messager);
        when(context.getElementUtils()).thenReturn(elements);
        processor = new InverseRelationshipProcessor(context, support);

        SchemaModel schemaModel = mock(SchemaModel.class);
        entities = new HashMap<>();
        when(schemaModel.getEntities()).thenReturn(entities);
        when(context.getSchemaModel()).thenReturn(schemaModel);

        attr = mock(AttributeDescriptor.class);
        owner = mock(EntityModel.class);
        when(owner.getEntityName()).thenReturn("com.example.Owner");
    }

    @Nested
    @DisplayName("supports()")
    class Supports {

        @Test
        void oneToMany_with_mappedBy_true() {
            OneToMany ann = mock(OneToMany.class);
            when(ann.mappedBy()).thenReturn("owner");
            when(attr.getAnnotation(OneToMany.class)).thenReturn(ann);
            assertTrue(processor.supports(attr));
        }

        @Test
        void manyToMany_with_mappedBy_true() {
            ManyToMany ann = mock(ManyToMany.class);
            when(ann.mappedBy()).thenReturn("roles");
            when(attr.getAnnotation(OneToMany.class)).thenReturn(null);
            when(attr.getAnnotation(ManyToMany.class)).thenReturn(ann);
            assertTrue(processor.supports(attr));
        }

        @Test
        void oneToOne_with_mappedBy_true() {
            OneToOne ann = mock(OneToOne.class);
            when(ann.mappedBy()).thenReturn("profile");
            when(attr.getAnnotation(OneToMany.class)).thenReturn(null);
            when(attr.getAnnotation(ManyToMany.class)).thenReturn(null);
            when(attr.getAnnotation(OneToOne.class)).thenReturn(ann);
            assertTrue(processor.supports(attr));
        }

        @Test
        void no_annotations_or_empty_mappedBy_false() {
            when(attr.getAnnotation(OneToMany.class)).thenReturn(null);
            when(attr.getAnnotation(ManyToMany.class)).thenReturn(null);
            when(attr.getAnnotation(OneToOne.class)).thenReturn(null);
            assertFalse(processor.supports(attr));
        }
    }

    @Nested
    @DisplayName("process inverse OneToMany")
    class OneToManyInverse {

        @Test
        void resolves_target_and_finds_mappedBy_logs_NOTE() {
            ManyToMany ann = mock(ManyToMany.class);
            when(ann.mappedBy()).thenReturn("groups");
            when(attr.getAnnotation(OneToMany.class)).thenReturn(null);
            when(attr.getAnnotation(ManyToMany.class)).thenReturn(ann);
            when(attr.getAnnotation(OneToOne.class)).thenReturn(null);
            when(attr.name()).thenReturn("users");

            TypeElement targetEl = mock(TypeElement.class);
            Name qn = mock(Name.class);
            when(qn.toString()).thenReturn("com.example.User.java");
            when(targetEl.getQualifiedName()).thenReturn(qn);

            // ★ elements.getTypeElement(...)도 반환하도록
            when(elements.getTypeElement("com.example.User.java")).thenReturn(targetEl);

            // ★ schemaModel.getEntities().put(...) 로 엔티티 존재하게
            EntityModel targetEntityModel = mock(EntityModel.class);
            entities.put("com.example.User.java", targetEntityModel);

            when(support.resolveTargetEntity(attr, null, null, null, ann))
                    .thenReturn(Optional.of(targetEl));

            when(context.isMappedByVisited("com.example.User.java", "groups")).thenReturn(false);
            doNothing().when(context).markMappedByVisited("com.example.User.java", "groups");
            doNothing().when(context).unmarkMappedByVisited("com.example.User.java", "groups");

            AttributeDescriptor mappedSide = mock(AttributeDescriptor.class);
            when(mappedSide.name()).thenReturn("groups");
            when(context.getCachedDescriptors(targetEl)).thenReturn(List.of(mappedSide));

            Element anyElem = mock(Element.class);
            when(attr.elementForDiagnostics()).thenReturn(anyElem);

            processor.process(attr, owner);

            verify(messager, atLeastOnce())
                    .printMessage(eq(Diagnostic.Kind.NOTE),
                            contains("@ManyToMany(mappedBy='groups')"));
            verify(messager, never())
                    .printMessage(eq(Diagnostic.Kind.WARNING), contains("Cannot find mappedBy attribute"), any());
        }

        @Test
        void cannot_resolve_target_logs_WARNING() {
            OneToMany ann = mock(OneToMany.class);
            when(ann.mappedBy()).thenReturn("owner");
            when(attr.getAnnotation(OneToMany.class)).thenReturn(ann);
            when(attr.name()).thenReturn("items");
            when(support.resolveTargetEntity(attr, null, null, ann, null)).thenReturn(Optional.empty());

            Element anyElem = mock(Element.class);
            when(attr.elementForDiagnostics()).thenReturn(anyElem);

            processor.process(attr, owner);

            verify(messager).printMessage(eq(Diagnostic.Kind.WARNING), contains("Cannot resolve target entity for inverse @OneToMany"), eq(anyElem));
        }

        @Test
        void target_resolved_but_mappedBy_missing_logs_WARNING() {
            OneToMany ann = mock(OneToMany.class);
            when(ann.mappedBy()).thenReturn("owner");
            when(attr.getAnnotation(OneToMany.class)).thenReturn(ann);
            when(attr.name()).thenReturn("items");

            TypeElement targetEl = mock(TypeElement.class);
            Name qn = mock(Name.class);
            when(qn.toString()).thenReturn("com.example.Target");
            when(targetEl.getQualifiedName()).thenReturn(qn);
            when(support.resolveTargetEntity(attr, null, null, ann, null)).thenReturn(Optional.of(targetEl));

            when(context.isMappedByVisited("com.example.Target", "owner")).thenReturn(false);
            doNothing().when(context).markMappedByVisited("com.example.Target", "owner");
            doNothing().when(context).unmarkMappedByVisited("com.example.Target", "owner");

            when(context.getCachedDescriptors(targetEl)).thenReturn(List.of());
            Element anyElem = mock(Element.class);
            when(attr.elementForDiagnostics()).thenReturn(anyElem);

            processor.process(attr, owner);

            verify(messager).printMessage(eq(Diagnostic.Kind.WARNING), contains("Cannot find mappedBy attribute 'owner'"), eq(anyElem));
        }

        @Test
        void cycle_detected_logs_WARNING_and_skips() {
            OneToMany ann = mock(OneToMany.class);
            when(ann.mappedBy()).thenReturn("owner");
            when(attr.getAnnotation(OneToMany.class)).thenReturn(ann);
            when(attr.name()).thenReturn("items");

            TypeElement targetEl = mock(TypeElement.class);
            Name qn = mock(Name.class);
            when(qn.toString()).thenReturn("com.example.Target");
            when(targetEl.getQualifiedName()).thenReturn(qn);
            when(support.resolveTargetEntity(attr, null, null, ann, null)).thenReturn(Optional.of(targetEl));

            when(context.isMappedByVisited("com.example.Target", "owner")).thenReturn(true);
            Element anyElem = mock(Element.class);
            when(attr.elementForDiagnostics()).thenReturn(anyElem);

            processor.process(attr, owner);

            verify(messager).printMessage(eq(Diagnostic.Kind.WARNING), contains("Detected cyclic mappedBy reference"));
        }
    }

    @Nested
    @DisplayName("process inverse ManyToMany")
    class ManyToManyInverse {

        @Test
        void resolves_target_and_finds_mappedBy_logs_NOTE() {
            ManyToMany ann = mock(ManyToMany.class);
            when(ann.mappedBy()).thenReturn("groups");
            when(attr.getAnnotation(OneToMany.class)).thenReturn(null);
            when(attr.getAnnotation(ManyToMany.class)).thenReturn(ann);
            when(attr.getAnnotation(OneToOne.class)).thenReturn(null);
            when(attr.name()).thenReturn("users");

            TypeElement targetEl = mock(TypeElement.class);
            Name qn = mock(Name.class);
            when(qn.toString()).thenReturn("com.example.User.java");
            when(elements.getTypeElement("com.example.User.java")).thenReturn(targetEl);
            when(targetEl.getQualifiedName()).thenReturn(qn);
            when(support.resolveTargetEntity(attr, null, null, null, ann)).thenReturn(Optional.of(targetEl));

            when(context.isMappedByVisited("com.example.User.java", "groups")).thenReturn(false);
            doNothing().when(context).markMappedByVisited("com.example.User.java", "groups");
            doNothing().when(context).unmarkMappedByVisited("com.example.User.java", "groups");

            EntityModel targetEntityModel = mock(EntityModel.class);
            entities.put("com.example.User.java", targetEntityModel);
            when(elements.getTypeElement("com.example.User.java")).thenReturn(targetEl);
            when(support.resolveTargetEntity(attr, null, null, null, ann))
                    .thenReturn(Optional.of(targetEl));

            AttributeDescriptor mappedSide = mock(AttributeDescriptor.class);
            when(mappedSide.name()).thenReturn("groups");
            when(context.getCachedDescriptors(targetEl)).thenReturn(List.of(mappedSide));

            Element anyElem = mock(Element.class);
            when(attr.elementForDiagnostics()).thenReturn(anyElem);

            processor.process(attr, owner);

            verify(messager, atLeastOnce()).printMessage(eq(Diagnostic.Kind.NOTE), contains("@ManyToMany(mappedBy='groups')"));
        }
    }

    @Nested
    @DisplayName("process inverse OneToOne")
    class OneToOneInverse {

        @Test
        void resolves_target_and_finds_mappedBy_logs_NOTE() {
            OneToOne ann = mock(OneToOne.class);
            when(ann.mappedBy()).thenReturn("owner");
            when(attr.getAnnotation(OneToMany.class)).thenReturn(null);
            when(attr.getAnnotation(ManyToMany.class)).thenReturn(null);
            when(attr.getAnnotation(OneToOne.class)).thenReturn(ann);
            when(attr.name()).thenReturn("profile");

            TypeElement targetEl = mock(TypeElement.class);
            Name qn = mock(Name.class);
            when(qn.toString()).thenReturn("com.example.Profile");
            when(targetEl.getQualifiedName()).thenReturn(qn);
            when(support.resolveTargetEntity(attr, null, ann, null, null)).thenReturn(Optional.of(targetEl));

            when(context.isMappedByVisited("com.example.Profile", "owner")).thenReturn(false);
            doNothing().when(context).markMappedByVisited("com.example.Profile", "owner");
            doNothing().when(context).unmarkMappedByVisited("com.example.Profile", "owner");

            EntityModel targetEntityModel = mock(EntityModel.class);
            entities.put("com.example.Profile", targetEntityModel);
            when(elements.getTypeElement("com.example.Profile")).thenReturn(targetEl);
            when(support.resolveTargetEntity(attr, null, ann, null, null))
                    .thenReturn(Optional.of(targetEl));

            AttributeDescriptor mappedSide = mock(AttributeDescriptor.class);
            when(mappedSide.name()).thenReturn("owner");
            doReturn(List.of(mappedSide)).when(context).getCachedDescriptors(targetEl);

            Element anyElem = mock(Element.class);
            when(attr.elementForDiagnostics()).thenReturn(anyElem);

            processor.process(attr, owner);

            verify(messager, atLeastOnce()).printMessage(eq(Diagnostic.Kind.NOTE), contains("@OneToOne(mappedBy='owner')"));
        }

        @Test
        void descriptor_cache_miss_logs_WARNING() {
            OneToOne ann = mock(OneToOne.class);
            when(ann.mappedBy()).thenReturn("owner");
            when(attr.getAnnotation(OneToMany.class)).thenReturn(null);
            when(attr.getAnnotation(ManyToMany.class)).thenReturn(null);
            when(attr.getAnnotation(OneToOne.class)).thenReturn(ann);
            when(attr.name()).thenReturn("profile");

            TypeElement targetEl = mock(TypeElement.class);
            Name qn = mock(Name.class);
            when(qn.toString()).thenReturn("com.example.Profile");
            when(targetEl.getQualifiedName()).thenReturn(qn);
            when(elements.getTypeElement("com.example.Profile")).thenReturn(targetEl);
            when(support.resolveTargetEntity(attr, null, ann, null, null)).thenReturn(Optional.of(targetEl));

            when(context.isMappedByVisited("com.example.Profile", "owner")).thenReturn(false);
            doNothing().when(context).markMappedByVisited("com.example.Profile", "owner");
            doNothing().when(context).unmarkMappedByVisited("com.example.Profile", "owner");

            entities.put("com.example.Profile", mock(EntityModel.class));
            when(context.getCachedDescriptors(same(targetEl))).thenReturn(null);

            Element anyElem = mock(Element.class);
            when(attr.elementForDiagnostics()).thenReturn(anyElem);

            processor.process(attr, owner);

            verify(messager, atLeastOnce()).printMessage(
                    eq(Diagnostic.Kind.WARNING),
                    contains("Descriptor cache miss for target entity com.example.Profile")
            );

            verify(messager, atLeastOnce()).printMessage(
                    eq(Diagnostic.Kind.WARNING),
                    contains("Cannot find mappedBy attribute 'owner' on target entity com.example.Profile for inverse @OneToOne"),
                    any()
            );
        }
    }
}
