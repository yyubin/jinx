package org.jinx.processor;

import com.google.testing.compile.Compilation;
import org.jinx.model.EntityModel;
import org.jinx.model.SchemaModel;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bug 1 회귀 테스트: 백킹 필드 없는 getter 메서드를 영속 컬럼으로 잘못 생성하는 버그.
 *
 * <p>수정 위치: {@code AttributeDescriptorFactory.selectAttributeDescriptor()} — FIELD access
 * fallback에서 백킹 필드 없는 getter에 대한 {@code createPropertyDescriptor()} 호출 제거.
 *
 * <p>재현 조건:
 * <ul>
 *   <li>FIELD access 모드 엔티티 (기본값 — {@code @Id}가 필드에 있는 경우)</li>
 *   <li>{@code public boolean isXxx()} / {@code public T getXxx()} 형태의 메서드</li>
 *   <li>해당 메서드에 대응하는 백킹 필드 없음</li>
 *   <li>{@code @Transient} 없음</li>
 * </ul>
 */
class GetterPhantomColumnTest extends AbstractProcessorTest {

    /**
     * isSuccessful(), isRefundable(), getDisplayName() 은 백킹 필드가 없으므로
     * DDL 컬럼이 생성되어서는 안 된다.
     * 실제 영속 컬럼 id, status 만 생성되어야 한다.
     */
    @Test
    void computedGettersWithoutBackingField_shouldNotCreateColumns() {
        Compilation compilation = compile(
                source("entities/getter/PaymentStatus.java"),
                source("entities/getter/Payment.java")
        );

        Optional<SchemaModel> schemaOpt = assertCompilationSuccessAndGetSchema(compilation);
        assertThat(schemaOpt).isPresent();

        EntityModel payment = schemaOpt.get().getEntities().get("entities.getter.Payment");
        assertThat(payment).as("Payment entity should be present").isNotNull();

        Set<String> columnNames = payment.getColumns().values().stream()
                .map(col -> col.getColumnName())
                .collect(Collectors.toSet());

        // ── 실제 영속 컬럼은 존재해야 함 ──────────────────────────────
        assertThat(columnNames)
                .as("Actual persistent column 'id' must exist")
                .contains("id");
        assertThat(columnNames)
                .as("Actual persistent column 'status' must exist")
                .contains("status");

        // ── 허위 컬럼은 존재하면 안 됨 ────────────────────────────────
        assertThat(columnNames)
                .as("isSuccessful() has no backing field → 'successful' column must NOT be created")
                .doesNotContain("successful");

        assertThat(columnNames)
                .as("isRefundable() has no backing field → 'refundable' column must NOT be created")
                .doesNotContain("refundable");

        assertThat(columnNames)
                .as("getDisplayName() has no backing field → 'displayName' column must NOT be created")
                .doesNotContain("displayName");

        // ── 컬럼 수는 정확히 2개여야 함 ─────────────────────────────
        assertThat(payment.getColumns())
                .as("Payment must have exactly 2 columns: id, status")
                .hasSize(2);
    }

    /**
     * id 컬럼이 PK로 설정되고, status 컬럼이 non-nullable 인지 추가 검증.
     */
    @Test
    void persistentColumnsAreCorrectlyMapped() {
        Compilation compilation = compile(
                source("entities/getter/PaymentStatus.java"),
                source("entities/getter/Payment.java")
        );

        EntityModel payment = assertCompilationSuccessAndGetSchema(compilation)
                .orElseThrow().getEntities().get("entities.getter.Payment");

        assertThat(payment.findColumn("payments", "id"))
                .as("id column must be PK")
                .satisfies(col -> assertThat(col.isPrimaryKey()).isTrue());

        assertThat(payment.findColumn("payments", "status"))
                .as("status column must be non-nullable")
                .satisfies(col -> assertThat(col.isNullable()).isFalse());
    }
}
