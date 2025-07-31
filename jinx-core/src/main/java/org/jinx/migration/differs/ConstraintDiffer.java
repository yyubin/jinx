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
                .filter(c -> c.getType() == target.getType() &&
                        equalIgnoreOrder(c.getColumns(), target.getColumns()) &&
                        Optional.ofNullable(c.getReferencedTable()).equals(Optional.ofNullable(target.getReferencedTable())) &&
                        equalIgnoreOrder(c.getReferencedColumns(), target.getReferencedColumns()) &&
                        c.getOnDelete() == target.getOnDelete() &&
                        c.getOnUpdate() == target.getOnUpdate())
                .findFirst()
                .orElse(null);
    }

    private boolean equalIgnoreOrder(List<String> a, List<String> b) {
        return Optional.ofNullable(a)
                .map(aList -> Optional.ofNullable(b)
                        .map(bList -> new HashSet<>(aList).equals(new HashSet<>(bList)))
                        .orElse(false))
                .orElse(Optional.ofNullable(b).isEmpty());
    }

    private boolean isConstraintEqual(ConstraintModel oldCons, ConstraintModel newCons) {
        return Optional.ofNullable(oldCons.getType()).equals(Optional.ofNullable(newCons.getType())) &&
                Optional.ofNullable(oldCons.getColumns()).equals(Optional.ofNullable(newCons.getColumns())) &&
                Optional.ofNullable(oldCons.getReferencedTable()).equals(Optional.ofNullable(newCons.getReferencedTable())) &&
                Optional.ofNullable(oldCons.getReferencedColumns()).equals(Optional.ofNullable(newCons.getReferencedColumns())) &&
                oldCons.getOnDelete() == newCons.getOnDelete() &&
                oldCons.getOnUpdate() == newCons.getOnUpdate();
    }

    private boolean isConstraintEqualWithName(ConstraintModel oldCons, ConstraintModel newCons) {
        return Optional.ofNullable(oldCons.getName()).equals(Optional.ofNullable(newCons.getName())) &&
                isConstraintEqual(oldCons, newCons);
    }

    private String getConstraintChangeDetail(ConstraintModel oldCons, ConstraintModel newCons) {
        StringBuilder detail = new StringBuilder();
        if (!Optional.ofNullable(oldCons.getName()).equals(Optional.ofNullable(newCons.getName()))) {
            detail.append("name changed from ").append(oldCons.getName()).append(" to ").append(newCons.getName()).append("; ");
        }
        if (!Optional.ofNullable(oldCons.getType()).equals(Optional.ofNullable(newCons.getType()))) {
            detail.append("type changed from ").append(oldCons.getType()).append(" to ").append(newCons.getType()).append("; ");
        }
        if (!Optional.ofNullable(oldCons.getColumns()).equals(Optional.ofNullable(newCons.getColumns()))) {
            detail.append("columns changed from ").append(oldCons.getColumns()).append(" to ").append(newCons.getColumns()).append("; ");
        }
        if (!Optional.ofNullable(oldCons.getReferencedTable()).equals(Optional.ofNullable(newCons.getReferencedTable()))) {
            detail.append("referencedTable changed from ").append(oldCons.getReferencedTable()).append(" to ").append(newCons.getReferencedTable()).append("; ");
        }
        if (!Optional.ofNullable(oldCons.getReferencedColumns()).equals(Optional.ofNullable(newCons.getReferencedColumns()))) {
            detail.append("referencedColumns changed from ").append(oldCons.getReferencedColumns()).append(" to ").append(newCons.getReferencedColumns()).append("; ");
        }
        if (oldCons.getOnDelete() != newCons.getOnDelete()) {
            detail.append("onDelete changed from ").append(oldCons.getOnDelete()).append(" to ").append(newCons.getOnDelete()).append("; ");
        }
        if (oldCons.getOnUpdate() != newCons.getOnUpdate()) {
            detail.append("onUpdate changed from ").append(oldCons.getOnUpdate()).append(" to ").append(newCons.getOnUpdate()).append("; ");
        }
        if (detail.length() > 2) {
            detail.setLength(detail.length() - 2);
        }
        return detail.toString();
    }
}