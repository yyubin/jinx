//package org.jinx.migration.differs;
//
//import org.jinx.model.ConstraintModel;
//import org.jinx.model.ConstraintType;
//import org.jinx.model.DiffResult;
//import org.jinx.model.EntityModel;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.DisplayName;
//import org.junit.jupiter.api.Test;
//
//import java.util.Collections;
//import java.util.List;
//
//import static org.junit.jupiter.api.Assertions.*;
//
//
//class ConstraintDifferTest {
//
//    private ConstraintDiffer constraintDiffer;
//    private EntityModel oldEntity;
//    private EntityModel newEntity;
//    private DiffResult.ModifiedEntity modifiedEntityResult;
//
//    @BeforeEach
//    void setUp() {
//        constraintDiffer = new ConstraintDiffer();
//        oldEntity = new EntityModel();
//        newEntity = new EntityModel();
//        modifiedEntityResult = DiffResult.ModifiedEntity.builder()
//                .oldEntity(oldEntity)
//                .newEntity(newEntity)
//                .build();
//    }
//
//    @Test
//    @DisplayName("제약 조건 변경이 없을 때 아무것도 감지하지 않아야 함")
//    void shouldDetectNoChanges_whenConstraintsAreIdentical() {
//        ConstraintModel constraint = createUniqueConstraint("uk_email", List.of("email"));
//        oldEntity.setConstraints(List.of(constraint));
//        newEntity.setConstraints(List.of(constraint));
//
//        constraintDiffer.diff(oldEntity, newEntity, modifiedEntityResult);
//
//        assertTrue(modifiedEntityResult.getConstraintDiffs().isEmpty(), "변경 사항이 없어야 합니다.");
//    }
//
//    @Test
//    @DisplayName("새로운 제약 조건이 추가되었을 때 'ADDED'로 감지해야 함")
//    void shouldDetectAddedConstraint() {
//        ConstraintModel newConstraint = createUniqueConstraint("uk_email", List.of("email"));
//        oldEntity.setConstraints(Collections.emptyList());
//        newEntity.setConstraints(List.of(newConstraint));
//
//        constraintDiffer.diff(oldEntity, newEntity, modifiedEntityResult);
//
//        assertEquals(1, modifiedEntityResult.getConstraintDiffs().size());
//        DiffResult.ConstraintDiff diff = modifiedEntityResult.getConstraintDiffs().get(0);
//        assertEquals(DiffResult.ConstraintDiff.Type.ADDED, diff.getType());
//        assertEquals("uk_email", diff.getConstraint().getName());
//    }
//
//    @Test
//    @DisplayName("기존 제약 조건이 삭제되었을 때 'DROPPED'로 감지해야 함")
//    void shouldDetectDroppedConstraint() {
//        ConstraintModel oldConstraint = createUniqueConstraint("uk_email", List.of("email"));
//        oldEntity.setConstraints(List.of(oldConstraint));
//        newEntity.setConstraints(Collections.emptyList());
//
//        constraintDiffer.diff(oldEntity, newEntity, modifiedEntityResult);
//
//        assertEquals(1, modifiedEntityResult.getConstraintDiffs().size());
//        DiffResult.ConstraintDiff diff = modifiedEntityResult.getConstraintDiffs().get(0);
//        assertEquals(DiffResult.ConstraintDiff.Type.DROPPED, diff.getType());
//        assertEquals("uk_email", diff.getConstraint().getName());
//    }
//
//    @Test
//    @DisplayName("제약 조건의 이름만 변경되었을 때 'MODIFIED'로 감지해야 함")
//    void shouldDetectModifiedConstraint_whenOnlyNameChanges() {
//        ConstraintModel oldConstraint = createUniqueConstraint("uk_email_old", List.of("email"));
//        ConstraintModel newConstraint = createUniqueConstraint("uk_email_new", List.of("email"));
//        oldEntity.setConstraints(List.of(oldConstraint));
//        newEntity.setConstraints(List.of(newConstraint));
//
//        constraintDiffer.diff(oldEntity, newEntity, modifiedEntityResult);
//
//        assertEquals(1, modifiedEntityResult.getConstraintDiffs().size());
//        DiffResult.ConstraintDiff diff = modifiedEntityResult.getConstraintDiffs().get(0);
//        assertEquals(DiffResult.ConstraintDiff.Type.MODIFIED, diff.getType());
//        assertEquals("uk_email_old", diff.getOldConstraint().getName());
//        assertEquals("uk_email_new", diff.getConstraint().getName());
//        assertEquals("name changed from uk_email_old to uk_email_new", diff.getChangeDetail());
//    }
//
//    @Test
//    @DisplayName("제약 조건의 컬럼이 변경되면 'DROPPED'과 'ADDED'로 감지해야 함")
//    void shouldDetectAsDropAndAdd_whenColumnChanges() {
//        // 이름은 같지만, 대상 컬럼이 달라 속성이 다름
//        ConstraintModel oldConstraint = createUniqueConstraint("uk_user_info", List.of("email"));
//        ConstraintModel newConstraint = createUniqueConstraint("uk_user_info", List.of("phone_number"));
//        oldEntity.setConstraints(List.of(oldConstraint));
//        newEntity.setConstraints(List.of(newConstraint));
//
//        constraintDiffer.diff(oldEntity, newEntity, modifiedEntityResult);
//
//        assertEquals(2, modifiedEntityResult.getConstraintDiffs().size(), "2개의 변경(drop, add)이 감지되어야 합니다.");
//
//        boolean droppedFound = modifiedEntityResult.getConstraintDiffs().stream()
//                .anyMatch(d -> d.getType() == DiffResult.ConstraintDiff.Type.DROPPED && d.getConstraint().getColumns().contains("email"));
//        boolean addedFound = modifiedEntityResult.getConstraintDiffs().stream()
//                .anyMatch(d -> d.getType() == DiffResult.ConstraintDiff.Type.ADDED && d.getConstraint().getColumns().contains("phone_number"));
//
//        assertTrue(droppedFound, "기존 제약 조건이 'DROPPED'로 감지되어야 합니다.");
//        assertTrue(addedFound, "새로운 제약 조건이 'ADDED'로 감지되어야 합니다.");
//    }
//
//    @Test
//    @DisplayName("UNIQUE 제약 조건의 column 순서만 바뀐 경우에도 동일하다고 간주해야 함")
//    void shouldTreatColumnOrderAsIrrelevant() {
//        ConstraintModel oldConstraint = createUniqueConstraint("uk_composite", List.of("email", "phone"));
//        ConstraintModel newConstraint = createUniqueConstraint("uk_composite", List.of("phone", "email")); // 순서만 바뀜
//
//        oldEntity.setConstraints(List.of(oldConstraint));
//        newEntity.setConstraints(List.of(newConstraint));
//
//        constraintDiffer.diff(oldEntity, newEntity, modifiedEntityResult);
//
//        assertTrue(modifiedEntityResult.getConstraintDiffs().isEmpty(), "컬럼 순서 변경만 있을 경우 변경으로 간주하지 않아야 합니다.");
//    }
//
//    @Test
//    @DisplayName("타입이 다른 제약조건은 다른 것으로 간주되어야 함 (DROP+ADD)")
//    void shouldNotMatchConstraintsWithDifferentTypes() {
//        ConstraintModel oldConstraint = createUniqueConstraint("user_constraint", List.of("id"));
//
//        ConstraintModel newConstraint = ConstraintModel.builder().build();
//        newConstraint.setName("user_constraint");
//        newConstraint.setType(ConstraintType.PRIMARY_KEY); // 타입을 UNIQUE에서 PRIMARY_KEY로 변경
//        newConstraint.setColumns(List.of("id"));
//
//        oldEntity.setConstraints(List.of(oldConstraint));
//        newEntity.setConstraints(List.of(newConstraint));
//
//        constraintDiffer.diff(oldEntity, newEntity, modifiedEntityResult);
//
//        assertEquals(2, modifiedEntityResult.getConstraintDiffs().size(), "타입이 다르면 별개의 제약조건으로 취급되어야 합니다.");
//        boolean dropped = modifiedEntityResult.getConstraintDiffs().stream()
//                .anyMatch(d -> d.getType() == DiffResult.ConstraintDiff.Type.DROPPED && d.getConstraint().getType() == ConstraintType.UNIQUE);
//        boolean added = modifiedEntityResult.getConstraintDiffs().stream()
//                .anyMatch(d -> d.getType() == DiffResult.ConstraintDiff.Type.ADDED && d.getConstraint().getType() == ConstraintType.PRIMARY_KEY);
//
//        assertTrue(dropped, "기존 UNIQUE 제약조건이 DROPPED 되어야 합니다.");
//        assertTrue(added, "새 PRIMARY_KEY 제약조건이 ADDED 되어야 합니다.");
//    }
//
//    @Test
//    @DisplayName("컬럼 리스트가 null인 제약조건은 다른 것으로 간주되어야 함")
//    void shouldHandleNullColumnLists() {
//        ConstraintModel oldConstraint = createUniqueConstraint("uk_user", List.of("id"));
//        ConstraintModel newConstraint = createUniqueConstraint("uk_user", null); // columns를 null로 설정
//
//        oldEntity.setConstraints(List.of(oldConstraint));
//        newEntity.setConstraints(List.of(newConstraint));
//
//        constraintDiffer.diff(oldEntity, newEntity, modifiedEntityResult);
//
//        // 컬럼 속성이 달라졌으므로 Drop + Add로 처리되어야 합니다.
//        assertEquals(2, modifiedEntityResult.getConstraintDiffs().size());
//    }
//
//    private ConstraintModel createUniqueConstraint(String name, List<String> columns) {
//        ConstraintModel constraint = ConstraintModel.builder().build();
//        constraint.setName(name);
//        constraint.setType(ConstraintType.UNIQUE);
//        constraint.setColumns(columns);
//        return constraint;
//    }
//}