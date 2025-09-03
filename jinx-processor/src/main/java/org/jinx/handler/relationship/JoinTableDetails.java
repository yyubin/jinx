package org.jinx.handler.relationship;

import org.jinx.model.EntityModel;

import java.util.Map;

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
