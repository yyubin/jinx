package org.jinx.migration.differs;

import org.jinx.model.ConstraintModel;
import org.jinx.model.ConstraintType;
import org.jinx.model.DiffResult;
import org.jinx.model.EntityModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;


class ConstraintDifferTest {

    private ConstraintDiffer constraintDiffer;
    private EntityModel oldEntity;
    private EntityModel newEntity;
    private DiffResult.ModifiedEntity modifiedEntityResult;

    @BeforeEach
    void setUp() {
        constraintDiffer = new ConstraintDiffer();
        oldEntity = new EntityModel();
        newEntity = new EntityModel();
        modifiedEntityResult = DiffResult.ModifiedEntity.builder()
                .oldEntity(oldEntity)
                .newEntity(newEntity)
                .build();
    }

    @Test
    @DisplayName("제약 조건 변경이 없을 때 아무것도 감지하지 않아야 함")
    void shouldDetectNoChanges_whenConstraintsAreIdentical() {
        ConstraintModel constraint = createUniqueConstraint("uk_email", List.of("email"));
        oldEntity.setConstraints(List.of(constraint));
        newEntity.setConstraints(List.of(constraint));

        constraintDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertTrue(modifiedEntityResult.getConstraintDiffs().isEmpty(), "변경 사항이 없어야 합니다.");
    }

    @Test
    @DisplayName("새로운 제약 조건이 추가되었을 때 'ADDED'로 감지해야 함")
    void shouldDetectAddedConstraint() {
        ConstraintModel newConstraint = createUniqueConstraint("uk_email", List.of("email"));
        oldEntity.setConstraints(Collections.emptyList());
        newEntity.setConstraints(List.of(newConstraint));

        constraintDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertEquals(1, modifiedEntityResult.getConstraintDiffs().size());
        DiffResult.ConstraintDiff diff = modifiedEntityResult.getConstraintDiffs().get(0);
        assertEquals(DiffResult.ConstraintDiff.Type.ADDED, diff.getType());
        assertEquals("uk_email", diff.getConstraint().getName());
    }

    @Test
    @DisplayName("기존 제약 조건이 삭제되었을 때 'DROPPED'로 감지해야 함")
    void shouldDetectDroppedConstraint() {
        ConstraintModel oldConstraint = createUniqueConstraint("uk_email", List.of("email"));
        oldEntity.setConstraints(List.of(oldConstraint));
        newEntity.setConstraints(Collections.emptyList());

        constraintDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertEquals(1, modifiedEntityResult.getConstraintDiffs().size());
        DiffResult.ConstraintDiff diff = modifiedEntityResult.getConstraintDiffs().get(0);
        assertEquals(DiffResult.ConstraintDiff.Type.DROPPED, diff.getType());
        assertEquals("uk_email", diff.getConstraint().getName());
    }

    @Test
    @DisplayName("제약 조건의 이름만 변경되었을 때 'MODIFIED'로 감지해야 함")
    void shouldDetectModifiedConstraint_whenOnlyNameChanges() {
        ConstraintModel oldConstraint = createUniqueConstraint("uk_email_old", List.of("email"));
        ConstraintModel newConstraint = createUniqueConstraint("uk_email_new", List.of("email"));
        oldEntity.setConstraints(List.of(oldConstraint));
        newEntity.setConstraints(List.of(newConstraint));

        constraintDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertEquals(1, modifiedEntityResult.getConstraintDiffs().size());
        DiffResult.ConstraintDiff diff = modifiedEntityResult.getConstraintDiffs().get(0);
        assertEquals(DiffResult.ConstraintDiff.Type.MODIFIED, diff.getType());
        assertEquals("uk_email_old", diff.getOldConstraint().getName());
        assertEquals("uk_email_new", diff.getConstraint().getName());
        assertTrue(diff.getChangeDetail().contains("name changed from uk_email_old to uk_email_new"));
    }

    @Test
    @DisplayName("제약 조건의 속성이 변경되면 'DROPPED'과 'ADDED'로 감지해야 함")
    void shouldDetectAsDropAndAdd_whenAttributeChanges() {
        // 이름은 같지만, 대상 컬럼이 달라 속성이 다름
        ConstraintModel oldConstraint = createUniqueConstraint("uk_user_info", List.of("email"));
        ConstraintModel newConstraint = createUniqueConstraint("uk_user_info", List.of("phone_number"));
        oldEntity.setConstraints(List.of(oldConstraint));
        newEntity.setConstraints(List.of(newConstraint));

        constraintDiffer.diff(oldEntity, newEntity, modifiedEntityResult);

        assertEquals(2, modifiedEntityResult.getConstraintDiffs().size(), "2개의 변경(drop, add)이 감지되어야 합니다.");

        boolean droppedFound = modifiedEntityResult.getConstraintDiffs().stream()
                .anyMatch(d -> d.getType() == DiffResult.ConstraintDiff.Type.DROPPED && d.getConstraint().getColumns().contains("email"));
        boolean addedFound = modifiedEntityResult.getConstraintDiffs().stream()
                .anyMatch(d -> d.getType() == DiffResult.ConstraintDiff.Type.ADDED && d.getConstraint().getColumns().contains("phone_number"));

        assertTrue(droppedFound, "기존 제약 조건이 'DROPPED'로 감지되어야 합니다.");
        assertTrue(addedFound, "새로운 제약 조건이 'ADDED'로 감지되어야 합니다.");
    }

    private ConstraintModel createUniqueConstraint(String name, List<String> columns) {
        ConstraintModel constraint = ConstraintModel.builder().build();
        constraint.setName(name);
        constraint.setType(ConstraintType.UNIQUE);
        constraint.setColumns(columns);
        return constraint;
    }
}
