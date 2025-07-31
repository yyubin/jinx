package org.jinx.migration.differs;

import org.jinx.model.ConstraintModel;
import org.jinx.model.DiffResult;
import org.jinx.model.EntityModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ConstraintDiffer implements EntityComponentDiffer {
    @Override
    public void diff(EntityModel oldEntity, EntityModel newEntity, DiffResult.ModifiedEntity result) {
        // Map matched constraints and track unmatched ones
        Map<ConstraintModel, ConstraintModel> matchedConstraints = new HashMap<>();
        List<ConstraintModel> oldUnmatched = new ArrayList<>(oldEntity.getConstraints());
        List<ConstraintModel> newUnmatched = new ArrayList<>();

        // Match constraints based on attributes (ignoring name)
        for (ConstraintModel newConstraint : newEntity.getConstraints()) {
            ConstraintModel oldMatch = findMatchingConstraint(oldUnmatched, newConstraint);
            if (oldMatch != null) {
                matchedConstraints.put(oldMatch, newConstraint);
                oldUnmatched.remove(oldMatch);
            } else {
                newUnmatched.add(newConstraint);
            }
        }

        // Handle modified constraints (including name changes)
        matchedConstraints.forEach((oldCons, newCons) -> {
            if (!isConstraintEqualWithName(oldCons, newCons)) {
                result.getConstraintDiffs().add(DiffResult.ConstraintDiff.builder()
                        .type(DiffResult.ConstraintDiff.Type.MODIFIED)
                        .constraint(newCons)
                        .oldConstraint(oldCons)
                        .changeDetail(getConstraintChangeDetail(oldCons, newCons))
                        .build());
            }
        });

        // Handle added constraints
        newUnmatched.forEach(cons -> result.getConstraintDiffs().add(
                DiffResult.ConstraintDiff.builder()
                        .type(DiffResult.ConstraintDiff.Type.ADDED)
                        .constraint(cons)
                        .build()));

        // Handle dropped constraints
        oldUnmatched.forEach(cons -> result.getConstraintDiffs().add(
                DiffResult.ConstraintDiff.builder()
                        .type(DiffResult.ConstraintDiff.Type.DROPPED)
                        .constraint(cons)
                        .build()));
    }

    private ConstraintModel findMatchingConstraint(List<ConstraintModel> pool, ConstraintModel target) {
        return pool.stream()
                .filter(c -> isConstraintLooselyMatching(c, target))
                .findFirst()
                .orElse(null);
    }

    private boolean isConstraintLooselyMatching(ConstraintModel a, ConstraintModel b) {
        return a.getType() == b.getType()
                && equalIgnoreOrder(a.getColumns(), b.getColumns());
    }

    private boolean equalIgnoreOrder(List<String> a, List<String> b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return new HashSet<>(a).equals(new HashSet<>(b));
    }

    private boolean isConstraintEqual(ConstraintModel oldCons, ConstraintModel newCons) {
        // Type and columns are already confirmed equal by the loose matching logic.
        // Foreign key details are handled by RelationshipDiffer.
        // Therefore, for this differ, if loosely matched, the core attributes are equal.
        return true;
    }

    private boolean isConstraintEqualWithName(ConstraintModel oldCons, ConstraintModel newCons) {
        return Optional.ofNullable(oldCons.getName()).equals(Optional.ofNullable(newCons.getName())) &&
                isConstraintEqual(oldCons, newCons);
    }

    private String getConstraintChangeDetail(ConstraintModel oldCons, ConstraintModel newCons) {
        StringBuilder detail = new StringBuilder();
        if (!Optional.ofNullable(oldCons.getName()).equals(Optional.ofNullable(newCons.getName()))) {
            detail.append("name changed from ").append(oldCons.getName()).append(" to ").append(newCons.getName());
        }
        return detail.toString();
    }
}