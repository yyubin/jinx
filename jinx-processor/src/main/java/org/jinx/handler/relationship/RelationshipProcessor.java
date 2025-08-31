package org.jinx.handler.relationship;

import org.jinx.descriptor.AttributeDescriptor;
import org.jinx.model.EntityModel;

/**
 * Interface for processing different types of relationship annotations
 */
public interface RelationshipProcessor {
    
    /**
     * Check if this processor can handle the given attribute descriptor
     * @param descriptor the attribute descriptor to check
     * @return true if this processor supports the descriptor's relationship annotations
     */
    boolean supports(AttributeDescriptor descriptor);
    
    /**
     * Process the relationship defined by the attribute descriptor
     * @param descriptor the attribute descriptor containing relationship annotations
     * @param ownerEntity the entity that owns this relationship
     */
    void process(AttributeDescriptor descriptor, EntityModel ownerEntity);

    int order();
}
