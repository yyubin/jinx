package org.jinx.processor;

import com.google.testing.compile.Compilation;
import jakarta.persistence.InheritanceType;
import org.jinx.model.EntityModel;
import org.jinx.model.RelationshipModel;
import org.jinx.model.SchemaModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.jinx.model.RelationshipType;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bug 2 회귀 테스트: JOINED 자식 테이블에 부모 컬럼이 중복 생성되는 버그.
 *
 * <p>수정 위치: {@code AttributeDescriptorFactory.collectAttributesFromHierarchy()} —
 * {@code @Entity} 수퍼클래스에서 재귀 탐색을 중단하도록 수정.
 *
 * <p>JPA 스펙(§2.3): JOINED 전략에서 자식 테이블은
 * <b>자식 전용 컬럼 + PK(FK)</b>만 가져야 하며, 부모 테이블의 컬럼을 중복으로 갖지 않는다.
 */
class JoinedInheritanceDuplicateColumnTest extends AbstractProcessorTest {

    private SchemaModel schema;

    @BeforeEach
    void compileAndLoadSchema() {
        Compilation compilation = compile(
                source("entities/joined/Vehicle.java"),
                source("entities/joined/Car.java"),
                source("entities/joined/Truck.java"),
                source("entities/joined/SportsCar.java")
        );

        Optional<SchemaModel> schemaOpt = assertCompilationSuccessAndGetSchema(compilation);
        assertThat(schemaOpt).as("Schema must be present").isPresent();
        schema = schemaOpt.get();
    }

    // ══════════════════════════════════════════════════════════
    // 루트 엔티티 (Vehicle) 검증
    // ══════════════════════════════════════════════════════════

    @Test
    void rootEntity_shouldHaveOwnColumnsOnly() {
        EntityModel vehicle = schema.getEntities().get("entities.joined.Vehicle");
        assertThat(vehicle).as("Vehicle entity must exist").isNotNull();

        Set<String> cols = columnNames(vehicle);
        assertThat(cols).as("Vehicle must have id and manufacturer")
                .containsExactlyInAnyOrder("id", "manufacturer");

        assertThat(vehicle.findColumn("vehicles", "id").isPrimaryKey())
                .as("Vehicle.id must be PK").isTrue();
    }

    @Test
    void rootEntity_shouldHaveJoinedInheritanceType() {
        EntityModel vehicle = schema.getEntities().get("entities.joined.Vehicle");
        assertThat(vehicle.getInheritance())
                .as("Vehicle must have JOINED inheritance type")
                .isEqualTo(InheritanceType.JOINED);
    }

    // ══════════════════════════════════════════════════════════
    // 1단계 자식 (Car) 검증
    // ══════════════════════════════════════════════════════════

    @Test
    void directChild_shouldHaveOnlyOwnColumnsAndFk() {
        EntityModel car = schema.getEntities().get("entities.joined.Car");
        assertThat(car).as("Car entity must exist").isNotNull();

        Set<String> cols = columnNames(car);

        // 자식 전용 컬럼 + PK(FK) 만 존재해야 함
        assertThat(cols).as("Car must contain its own columns: id(FK), numDoors")
                .contains("id", "numDoors");

        // 부모 컬럼 중복 금지
        assertThat(cols)
                .as("Car must NOT contain parent column 'manufacturer' (Bug 2 regression)")
                .doesNotContain("manufacturer");

        // 정확한 컬럼 수: id + numDoors = 2
        assertThat(car.getColumns())
                .as("Car must have exactly 2 columns: id(FK/PK), numDoors")
                .hasSize(2);
    }

    @Test
    void directChild_idShouldBePrimaryKey() {
        EntityModel car = schema.getEntities().get("entities.joined.Car");
        assertThat(car.findColumn("cars", "id"))
                .as("Car.id must be PK (FK to Vehicle)")
                .satisfies(col -> {
                    assertThat(col.isPrimaryKey()).isTrue();
                    assertThat(col.isNullable()).isFalse();
                });
    }

    @Test
    void directChild_shouldHaveForeignKeyToParent() {
        EntityModel car = schema.getEntities().get("entities.joined.Car");
        boolean hasFkToVehicle = car.getRelationships().values().stream()
                .anyMatch(rel -> "vehicles".equals(rel.getReferencedTable()));
        assertThat(hasFkToVehicle)
                .as("Car must have a FK relationship pointing to 'vehicles'")
                .isTrue();
    }

    @Test
    void anotherDirectChild_truck_shouldHaveNoParentColumns() {
        EntityModel truck = schema.getEntities().get("entities.joined.Truck");
        assertThat(truck).as("Truck entity must exist").isNotNull();

        Set<String> cols = columnNames(truck);
        assertThat(cols).contains("id", "payload");
        assertThat(cols)
                .as("Truck must NOT contain parent column 'manufacturer'")
                .doesNotContain("manufacturer");
        assertThat(truck.getColumns()).hasSize(2);
    }

    // ══════════════════════════════════════════════════════════
    // 2단계 자식 (SportsCar extends Car) 검증 — 다단계 상속
    // ══════════════════════════════════════════════════════════

    @Test
    void deepChild_shouldHaveOnlyOwnColumnsAndFk() {
        EntityModel sportsCar = schema.getEntities().get("entities.joined.SportsCar");
        assertThat(sportsCar).as("SportsCar entity must exist").isNotNull();

        Set<String> cols = columnNames(sportsCar);

        assertThat(cols).as("SportsCar must contain id(FK) and topSpeed")
                .contains("id", "topSpeed");

        // 조부모(Vehicle) 컬럼 중복 금지
        assertThat(cols)
                .as("SportsCar must NOT contain grandparent column 'manufacturer'")
                .doesNotContain("manufacturer");

        // 부모(Car) 컬럼 중복 금지
        assertThat(cols)
                .as("SportsCar must NOT contain parent column 'numDoors'")
                .doesNotContain("numDoors");

        // 정확한 컬럼 수: id + topSpeed = 2
        assertThat(sportsCar.getColumns())
                .as("SportsCar must have exactly 2 columns: id(FK/PK), topSpeed")
                .hasSize(2);
    }

    @Test
    void deepChild_shouldHaveForeignKeyToDirectParent() {
        // Bug 3 수정 후: findJoinedDirectParent()가 직계 @Entity 부모(Car)를 반환하므로
        // SportsCar의 FK는 루트(vehicles)가 아닌 직계 부모(cars)를 가리켜야 한다.
        EntityModel sportsCar = schema.getEntities().get("entities.joined.SportsCar");
        boolean hasFkToCars = sportsCar.getRelationships().values().stream()
                .anyMatch(rel -> "cars".equals(rel.getReferencedTable()));
        assertThat(hasFkToCars)
                .as("SportsCar must have a FK pointing to its direct parent 'cars', not to the root 'vehicles' (Bug 3)")
                .isTrue();

        // 루트(vehicles)를 직접 가리키는 잘못된 FK가 없어야 함
        boolean hasSpuriousFkToRoot = sportsCar.getRelationships().values().stream()
                .anyMatch(rel -> "vehicles".equals(rel.getReferencedTable()));
        assertThat(hasSpuriousFkToRoot)
                .as("SportsCar must NOT have a spurious FK directly to the root 'vehicles'")
                .isFalse();
    }

    // ══════════════════════════════════════════════════════════
    // FK 중복 방지 검증 (Bug: _1 suffix 중복 생성)
    // ══════════════════════════════════════════════════════════

    @Test
    void directChild_shouldHaveExactlyOneJoinedInheritanceFk() {
        EntityModel car = schema.getEntities().get("entities.joined.Car");
        long fkCount = car.getRelationships().values().stream()
                .filter(r -> r.getType() == RelationshipType.JOINED_INHERITANCE)
                .count();
        assertThat(fkCount)
                .as("Car must have exactly one JOINED_INHERITANCE FK — duplicate (_1 suffix) must not be generated")
                .isEqualTo(1);
    }

    @Test
    void deepChild_shouldHaveExactlyOneJoinedInheritanceFk() {
        EntityModel sportsCar = schema.getEntities().get("entities.joined.SportsCar");
        long fkCount = sportsCar.getRelationships().values().stream()
                .filter(r -> r.getType() == RelationshipType.JOINED_INHERITANCE)
                .count();
        assertThat(fkCount)
                .as("SportsCar must have exactly one JOINED_INHERITANCE FK — duplicate (_1 suffix) must not be generated")
                .isEqualTo(1);
    }

    // ══════════════════════════════════════════════════════════
    // 헬퍼
    // ══════════════════════════════════════════════════════════

    private static Set<String> columnNames(EntityModel entity) {
        return entity.getColumns().values().stream()
                .map(col -> col.getColumnName())
                .collect(Collectors.toSet());
    }
}
