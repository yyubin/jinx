package org.jinx.handler.relationship;

import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import org.jinx.context.ProcessingContext;
import org.jinx.descriptor.AttributeDescriptor;
import org.jinx.model.EntityModel;

import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.util.List;
import java.util.Optional;

/**
 * Processor for inverse side relationships (mappedBy is specified)
 */
public final class InverseRelationshipProcessor implements RelationshipProcessor {
    
    private final ProcessingContext context;
    private final RelationshipSupport support;
    
    public InverseRelationshipProcessor(ProcessingContext context, RelationshipSupport support) {
        this.context = context;
        this.support = support;
    }

    @Override
    public int order() {
        return 0;
    }

    @Override
    public boolean supports(AttributeDescriptor descriptor) {
        OneToMany oneToMany = descriptor.getAnnotation(OneToMany.class);
        if (oneToMany != null && !oneToMany.mappedBy().isEmpty()) {
            return true;
        }
        
        ManyToMany manyToMany = descriptor.getAnnotation(ManyToMany.class);
        if (manyToMany != null && !manyToMany.mappedBy().isEmpty()) {
            return true;
        }
        
        OneToOne oneToOne = descriptor.getAnnotation(OneToOne.class);
        if (oneToOne != null && !oneToOne.mappedBy().isEmpty()) {
            return true;
        }
        
        return false;
    }
    
    @Override
    public void process(AttributeDescriptor descriptor, EntityModel ownerEntity) {
        OneToMany oneToMany = descriptor.getAnnotation(OneToMany.class);
        ManyToMany manyToMany = descriptor.getAnnotation(ManyToMany.class);
        OneToOne oneToOne = descriptor.getAnnotation(OneToOne.class);
        
        if (oneToMany != null) {
            processInverseSideOneToMany(descriptor, ownerEntity, oneToMany);
        } else if (manyToMany != null) {
            processInverseSideManyToMany(descriptor, ownerEntity, manyToMany);
        } else if (oneToOne != null) {
            processInverseSideOneToOne(descriptor, ownerEntity, oneToOne);
        }
    }
    
    private void processInverseSideOneToMany(AttributeDescriptor attr, EntityModel ownerEntity, OneToMany oneToMany) {
        // Inverse side: no DDL artifacts, only logical relationship tracking
        String mappedBy = oneToMany.mappedBy();

        Optional<TypeElement> targetEntityElementOpt = support.resolveTargetEntity(attr, null, null, oneToMany, null);
        if (targetEntityElementOpt.isEmpty()) {
            context.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "Cannot resolve target entity for inverse @OneToMany(mappedBy='" + mappedBy + "') on " +
                            ownerEntity.getEntityName() + "." + attr.name() + ". Skipping logical relationship tracking.",
                    attr.elementForDiagnostics());
            return;
        }

        TypeElement targetEntityElement = targetEntityElementOpt.get();
        String targetEntityName = targetEntityElement.getQualifiedName().toString();

        // 대상 엔티티에서 mappedBy 속성이 존재하는지 확인합니다.
        // 참고: 대상 엔티티가 아직 처리되지 않은 경우(지연 처리 시나리오) 유효성 검사를 생략합니다.
        EntityModel targetEntityModel = context.getSchemaModel().getEntities().get(targetEntityName);
        if (targetEntityModel == null) {
            // 대상 엔티티가 아직 처리되지 않음 - 지연 처리(deferred processing)에서는 정상적인 상황
            // 유효성 검사는 조용히 건너뜁니다 (대상 엔티티가 준비된 이후 후속 단계에서 검증됨)
            return;
        }

        AttributeDescriptor mappedByAttr = findMappedByAttribute(ownerEntity.getEntityName(), targetEntityName, mappedBy);
        if (mappedByAttr == null) {
            // 대상 엔티티는 존재하지만 mappedBy 속성을 찾을 수 없는 경우 - 설정 오류
            context.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "Cannot find mappedBy attribute '" + mappedBy + "' on target entity " + targetEntityName +
                            " for inverse @OneToMany. Relationship may be incomplete.", attr.elementForDiagnostics());
            return;
        }

        // Log the inverse side relationship (no DDL generation)
        context.getMessager().printMessage(Diagnostic.Kind.NOTE,
                "Processing inverse @OneToMany(mappedBy='" + mappedBy + "') on " + ownerEntity.getEntityName() +
                        "." + attr.name() + " -> " + targetEntityName + ". No DDL artifacts generated (inverse side).");
    }
    
    private void processInverseSideManyToMany(AttributeDescriptor attr, EntityModel ownerEntity, ManyToMany manyToMany) {
        // Inverse side: mappedBy is specified, no DB artifacts should be created
        String mappedBy = manyToMany.mappedBy();

        Optional<TypeElement> targetEntityElementOpt = support.resolveTargetEntity(attr, null, null, null, manyToMany);
        if (targetEntityElementOpt.isEmpty()) {
            context.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "Cannot resolve target entity for inverse @ManyToMany(mappedBy='" + mappedBy + "') on " +
                            ownerEntity.getEntityName() + "." + attr.name() + ". Skipping logical relationship tracking.",
                    attr.elementForDiagnostics());
            return;
        }

        TypeElement targetEntityElement = targetEntityElementOpt.get();
        String targetEntityName = targetEntityElement.getQualifiedName().toString();

        // 대상 엔티티에서 mappedBy 속성이 존재하는지 확인합니다.
        // 참고: 대상 엔티티가 아직 처리되지 않은 경우(지연 처리 시나리오) 유효성 검사를 생략합니다.
        EntityModel targetEntityModel = context.getSchemaModel().getEntities().get(targetEntityName);
        if (targetEntityModel == null) {
            // 대상 엔티티가 아직 처리되지 않음 - 지연 처리(deferred processing)에서는 정상적인 상황입니다.
            // 유효성 검사를 조용히 건너뜁니다 (대상 엔티티가 준비된 이후 후속 단계에서 검증됨)
            return;
        }

        AttributeDescriptor mappedByAttr = findMappedByAttribute(ownerEntity.getEntityName(), targetEntityName, mappedBy);
        if (mappedByAttr == null) {
            // 대상 엔티티는 존재하지만 mappedBy 속성을 찾을 수 없는 경우 - 설정 오류
            context.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "Cannot find mappedBy attribute '" + mappedBy + "' on target entity " + targetEntityName +
                            " for inverse @ManyToMany. Relationship may be incomplete.", attr.elementForDiagnostics());
            return;
        }

        // Log the inverse side relationship (no DDL generation)
        context.getMessager().printMessage(Diagnostic.Kind.NOTE,
                "Processing inverse @ManyToMany(mappedBy='" + mappedBy + "') on " + ownerEntity.getEntityName() +
                        "." + attr.name() + " -> " + targetEntityName + ". No DDL artifacts generated (inverse side).");
    }
    
    private void processInverseSideOneToOne(AttributeDescriptor attr, EntityModel ownerEntity, OneToOne oneToOne) {
        // Inverse side: mappedBy is specified, no DB artifacts should be created
        String mappedBy = oneToOne.mappedBy();

        Optional<TypeElement> targetEntityElementOpt = support.resolveTargetEntity(attr, null, oneToOne, null, null);
        if (targetEntityElementOpt.isEmpty()) {
            context.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "Cannot resolve target entity for inverse @OneToOne(mappedBy='" + mappedBy + "') on " +
                            ownerEntity.getEntityName() + "." + attr.name() + ". Skipping logical relationship tracking.",
                    attr.elementForDiagnostics());
            return;
        }

        TypeElement targetEntityElement = targetEntityElementOpt.get();
        String targetEntityName = targetEntityElement.getQualifiedName().toString();

        // Verify the mappedBy attribute exists on the target entity
        // Note: Skip validation if target entity hasn't been processed yet (deferred processing scenario)
        EntityModel targetEntityModel = context.getSchemaModel().getEntities().get(targetEntityName);
        if (targetEntityModel == null) {
            // Target entity not processed yet - this is normal in deferred processing
            // Skip validation silently (will be validated in a later pass when target is ready)
            return;
        }

        AttributeDescriptor mappedByAttr = findMappedByAttribute(ownerEntity.getEntityName(), targetEntityName, mappedBy);
        if (mappedByAttr == null) {
            // Target entity exists but mappedBy attribute not found - this is a configuration error
            context.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "Cannot find mappedBy attribute '" + mappedBy + "' on target entity " + targetEntityName +
                            " for inverse @OneToOne. Relationship may be incomplete.", attr.elementForDiagnostics());
            return;
        }

        // Log the inverse side relationship (no DDL generation)
        context.getMessager().printMessage(Diagnostic.Kind.NOTE,
                "Processing inverse @OneToOne(mappedBy='" + mappedBy + "') on " + ownerEntity.getEntityName() +
                        "." + attr.name() + " -> " + targetEntityName + ". No DDL artifacts generated (inverse side).");
    }

    /**
     * AttributeDescriptorFactory.extractAttributeName()과 동일한 네이밍 규칙을 사용하여
     * 대상 엔티티에서 대응되는 속성 디스크립터를 찾습니다.
     * 캐싱(caching)과 순환 감지(cycle detection)을 지원합니다.
     */
    private AttributeDescriptor findMappedByAttribute(String ownerEntityName, String targetEntityName, String mappedByAttributeName) {
        // Check for infinite recursion
        if (context.isMappedByVisited(targetEntityName, mappedByAttributeName)) {
            context.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "Detected cyclic mappedBy reference: " + ownerEntityName + " -> " +
                            targetEntityName + "." + mappedByAttributeName + ". Breaking cycle to prevent infinite recursion.");
            return null;
        }

        // Mark this relationship as being visited
        context.markMappedByVisited(targetEntityName, mappedByAttributeName);

        try {
            EntityModel targetEntity = context.getSchemaModel().getEntities().get(targetEntityName);
            if (targetEntity == null) {
                return null;
            }

            TypeElement targetTypeElement = context.getElementUtils().getTypeElement(targetEntityName);
            if (targetTypeElement == null) {
                return null;
            }

            // Get cached descriptors to avoid re-computation
            List<AttributeDescriptor> targetDescriptors = context.getCachedDescriptors(targetTypeElement);
            if (targetDescriptors == null) {
                context.getMessager().printMessage(Diagnostic.Kind.WARNING,
                        "Descriptor cache miss for target entity " + targetEntityName +
                                " while resolving mappedBy '" + mappedByAttributeName + "'. Skipping.");
                return null;
            }

            // Find attribute with matching name using the same naming convention
            return targetDescriptors.stream()
                    .filter(desc -> desc.name().equals(mappedByAttributeName))
                    .findFirst()
                    .orElse(null);
        } finally {
            context.unmarkMappedByVisited(targetEntityName, mappedByAttributeName);
        }
    }
}