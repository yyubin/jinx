package org.jinx.handler.relationship;

import org.jinx.model.EntityModel;

import java.util.Map;

/**
 * Details for creating or validating a join table in many-to-many or one-to-many relationships
 *
 * @param joinTableName            the name of the join table
 * @param ownerFkToPkMap          mapping from owner-side FK column names to owner entity PK column names
 * @param inverseFkToPkMap        mapping from inverse-side FK column names to referenced entity PK column names
 * @param ownerEntity             the owning entity model
 * @param referencedEntity        the referenced (target) entity model
 * @param ownerFkConstraintName   explicit FK constraint name for the owner side (null if not specified)
 * @param inverseFkConstraintName explicit FK constraint name for the inverse side (null if not specified)
 * @param ownerNoConstraint       true if owner-side FK should not create a database constraint
 * @param inverseNoConstraint     true if inverse-side FK should not create a database constraint
 */
public record JoinTableDetails(
        String joinTableName,
        Map<String, String> ownerFkToPkMap,
        Map<String, String> inverseFkToPkMap,
        EntityModel ownerEntity,
        EntityModel referencedEntity,
        String ownerFkConstraintName,
        String inverseFkConstraintName,
        boolean ownerNoConstraint,
        boolean inverseNoConstraint
) {

}
