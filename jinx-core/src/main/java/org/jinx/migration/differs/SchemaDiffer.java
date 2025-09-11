package org.jinx.migration.differs;

import org.jinx.model.DiffResult;
import org.jinx.model.SchemaModel;
import org.jinx.model.naming.CaseNormalizer;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class SchemaDiffer {
    private final List<Differ> differs;
    private final EntityModificationDiffer entityModificationDiffer;
    private final CaseNormalizer normalizer;

    public SchemaDiffer() {
        this(CaseNormalizer.lower());
    }

    public SchemaDiffer(CaseNormalizer normalizer) {
        this(normalizer, createDefaultDiffers(normalizer));
    }

    public SchemaDiffer(CaseNormalizer normalizer, List<Differ> differs) {
        this.normalizer = Objects.requireNonNull(normalizer, "normalizer must not be null");
        this.differs = List.copyOf(Objects.requireNonNull(differs, "differs must not be null"));

        // FIX: 항상 SchemaDiffer의 normalizer로 생성(일관성)
        this.entityModificationDiffer = new EntityModificationDiffer(this.normalizer);
    }

    /**
     * 기본 differs 생성 - 파이프라인 순서 고정
     * 1. TableDiffer (테이블 추가/삭제/이름변경)
     * 2. EntityModificationDiffer (엔티티 내용 수정)
     * 3. SequenceDiffer (시퀀스)
     * 4. TableGeneratorDiffer (테이블 제너레이터)
     */
    private static List<Differ> createDefaultDiffers(CaseNormalizer normalizer) {
        return List.of(
                new TableDiffer(),
                new EntityModificationDiffer(normalizer),
                new SequenceDiffer(),
                new TableGeneratorDiffer()
        );
    }

    public DiffResult diff(SchemaModel oldSchema, SchemaModel newSchema) {
        Objects.requireNonNull(oldSchema, "oldSchema must not be null");
        Objects.requireNonNull(newSchema, "newSchema must not be null");

        DiffResult result = DiffResult.builder().build();

        // 1차: 표준 파이프라인 실행 (순서 고정)
        for (Differ differ : differs) {
            executeDifferSafely(differ, oldSchema, newSchema, result);
        }

        // 2차: 리네임된 테이블 쌍에 대해 '내용 변경'도 비교 (누락 방지)
        if (!result.getRenamedTables().isEmpty()) {
            runRenamedPairsModification(oldSchema, newSchema, result);
        }

        return result;
    }

    private void runRenamedPairsModification(SchemaModel oldSchema, SchemaModel newSchema, DiffResult result) {
        result.getRenamedTables().forEach(rt -> {
            try {
                // EntityModificationDiffer에 보조 메서드가 있다고 가정 (아래 참고)
                entityModificationDiffer.diffPair(rt.getOldEntity(), rt.getNewEntity(), result);
            } catch (Exception e) {
                result.getWarnings().add(String.format(
                        "Renamed pair diff failed: %s -> %s (%s: %s)",
                        rt.getOldEntity().getEntityName(),
                        rt.getNewEntity().getEntityName(),
                        e.getClass().getSimpleName(),
                        e.getMessage()
                ));
            }
        });
    }

    private void executeDifferSafely(Differ differ, SchemaModel oldSchema, SchemaModel newSchema, DiffResult result) {
        try {
            differ.diff(oldSchema, newSchema, result);
        } catch (Exception e) {
            result.getWarnings().add(String.format(
                    "Differ failed: %s (%s: %s)",
                    differ.getClass().getSimpleName(),
                    e.getClass().getSimpleName(),
                    e.getMessage()
            ));
        }
    }


}
