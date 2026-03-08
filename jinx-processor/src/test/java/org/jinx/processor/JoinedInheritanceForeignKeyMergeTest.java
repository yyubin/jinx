package org.jinx.processor;

import com.google.testing.compile.Compilation;
import jakarta.persistence.InheritanceType;
import org.jinx.model.EntityModel;
import org.jinx.model.RelationshipModel;
import org.jinx.model.RelationshipType;
import org.jinx.model.SchemaModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CodeRabbit 리뷰 대응 회귀 테스트:
 * processSingleJoinedChild()의 의미적 중복 guard가 @ForeignKey 설정을
 * (noConstraint / explicitName) 유실시키지 않는지 검증한다.
 *
 * <p>버그 시나리오:
 * EntityHandler.processJoinTable()이 먼저 실행되어 자동 생성 이름과
 * noConstraint=false 로 FK를 등록한 뒤, InheritanceHandler.processSingleJoinedChild()가
 * 중복으로 감지하고 early return 하면 @PrimaryKeyJoinColumn(foreignKey=...) 설정이 소실됐다.
 *
 * <p>수정 후 동작:
 * 중복 감지 시 기존 RelationshipModel에 @ForeignKey 설정을 merge 한 뒤 return 한다.
 */
class JoinedInheritanceForeignKeyMergeTest extends AbstractProcessorTest {

    private SchemaModel schema;

    @BeforeEach
    void compileAndLoadSchema() {
        Compilation compilation = compile(
                source("entities/joined/fk/Machine.java"),
                source("entities/joined/fk/Printer.java"),
                source("entities/joined/fk/Scanner.java")
        );
        Optional<SchemaModel> schemaOpt = assertCompilationSuccessAndGetSchema(compilation);
        assertThat(schemaOpt).as("Schema must be present").isPresent();
        schema = schemaOpt.get();
    }

    // ══════════════════════════════════════════════════════════
    // 공통 구조 검증
    // ══════════════════════════════════════════════════════════

    @Test
    void printer_shouldHaveJoinedInheritanceType() {
        EntityModel printer = schema.getEntities().get("entities.joined.fk.Printer");
        assertThat(printer).isNotNull();
        assertThat(printer.getInheritance()).isEqualTo(InheritanceType.JOINED);
    }

    @Test
    void scanner_shouldHaveJoinedInheritanceType() {
        EntityModel scanner = schema.getEntities().get("entities.joined.fk.Scanner");
        assertThat(scanner).isNotNull();
        assertThat(scanner.getInheritance()).isEqualTo(InheritanceType.JOINED);
    }

    // ══════════════════════════════════════════════════════════
    // FK 단건 검증 (중복 _1 없음)
    // ══════════════════════════════════════════════════════════

    @Test
    void printer_shouldHaveExactlyOneJoinedInheritanceFk() {
        EntityModel printer = schema.getEntities().get("entities.joined.fk.Printer");
        long count = joinedFkCount(printer);
        assertThat(count)
                .as("Printer must have exactly one JOINED_INHERITANCE FK (no _1 duplicate)")
                .isEqualTo(1);
    }

    @Test
    void scanner_shouldHaveExactlyOneJoinedInheritanceFk() {
        EntityModel scanner = schema.getEntities().get("entities.joined.fk.Scanner");
        long count = joinedFkCount(scanner);
        assertThat(count)
                .as("Scanner must have exactly one JOINED_INHERITANCE FK (no _1 duplicate)")
                .isEqualTo(1);
    }

    // ══════════════════════════════════════════════════════════
    // explicitName merge 검증
    // ══════════════════════════════════════════════════════════

    @Test
    void printer_joinedFk_shouldUseExplicitConstraintName() {
        EntityModel printer = schema.getEntities().get("entities.joined.fk.Printer");
        RelationshipModel fk = findSingleJoinedFk(printer);

        assertThat(fk.getConstraintName())
                .as("Printer FK must use the name declared in @ForeignKey(name = \"fk_printer_machine\"), " +
                    "regardless of whether EntityHandler or InheritanceHandler registered it first")
                .isEqualTo("fk_printer_machine");
    }

    @Test
    void printer_joinedFk_shouldNotBeNoConstraint() {
        EntityModel printer = schema.getEntities().get("entities.joined.fk.Printer");
        RelationshipModel fk = findSingleJoinedFk(printer);

        assertThat(fk.isNoConstraint())
                .as("Printer FK has no ConstraintMode.NO_CONSTRAINT — noConstraint must be false")
                .isFalse();
    }

    // ══════════════════════════════════════════════════════════
    // noConstraint merge 검증
    // ══════════════════════════════════════════════════════════

    @Test
    void scanner_joinedFk_shouldHaveNoConstraintTrue() {
        EntityModel scanner = schema.getEntities().get("entities.joined.fk.Scanner");
        RelationshipModel fk = findSingleJoinedFk(scanner);

        assertThat(fk.isNoConstraint())
                .as("Scanner FK is declared with ConstraintMode.NO_CONSTRAINT — noConstraint must be true, " +
                    "even if EntityHandler registered the FK first with noConstraint=false")
                .isTrue();
    }

    @Test
    void scanner_joinedFk_referencedTable_shouldBeMachines() {
        EntityModel scanner = schema.getEntities().get("entities.joined.fk.Scanner");
        RelationshipModel fk = findSingleJoinedFk(scanner);

        assertThat(fk.getReferencedTable())
                .as("Scanner FK must still reference the parent table 'machines' after merge")
                .isEqualTo("machines");
    }

    // ══════════════════════════════════════════════════════════
    // 헬퍼
    // ══════════════════════════════════════════════════════════

    private static long joinedFkCount(EntityModel entity) {
        return entity.getRelationships().values().stream()
                .filter(r -> r.getType() == RelationshipType.JOINED_INHERITANCE)
                .count();
    }

    private static RelationshipModel findSingleJoinedFk(EntityModel entity) {
        List<RelationshipModel> fks = entity.getRelationships().values().stream()
                .filter(r -> r.getType() == RelationshipType.JOINED_INHERITANCE)
                .toList();
        assertThat(fks).as("Expected exactly one JOINED_INHERITANCE FK on " + entity.getEntityName())
                .hasSize(1);
        return fks.get(0);
    }
}
