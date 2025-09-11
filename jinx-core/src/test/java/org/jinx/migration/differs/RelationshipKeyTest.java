package org.jinx.migration.differs;

import org.jinx.migration.differs.model.RelationshipKey;
import org.jinx.model.RelationshipModel;
import org.jinx.model.RelationshipType;
import org.jinx.model.naming.CaseNormalizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RelationshipKeyTest {

    @Test
    @DisplayName("대소문자가 다른 동일한 관계는 같은 키를 생성해야 함")
    void shouldGenerateSameKeyForCaseInsensitiveRelationships() {
        RelationshipModel rel1 = RelationshipModel.builder()
                .tableName("User")
                .columns(List.of("TEAM_ID"))
                .referencedTable("Team")
                .referencedColumns(List.of("ID"))
                .build();

        RelationshipModel rel2 = RelationshipModel.builder()
                .tableName("user")
                .columns(List.of("team_id"))
                .referencedTable("team")
                .referencedColumns(List.of("id"))
                .build();

        CaseNormalizer normalizer = CaseNormalizer.lower();
        RelationshipKey key1 = RelationshipKey.of(rel1, normalizer);
        RelationshipKey key2 = RelationshipKey.of(rel2, normalizer);

        assertEquals(key1, key2);
        assertEquals(key1.hashCode(), key2.hashCode());
    }

    @Test
    @DisplayName("컬럼 순서가 다른 관계도 정렬되어 같은 키를 생성해야 함")
    void shouldGenerateSameKeyForDifferentColumnOrder() {
        RelationshipModel rel1 = RelationshipModel.builder()
                .tableName("User")
                .columns(List.of("team_id", "dept_id"))
                .referencedTable("Team")
                .referencedColumns(List.of("id", "dept_id"))
                .build();

        RelationshipModel rel2 = RelationshipModel.builder()
                .tableName("User")
                .columns(List.of("dept_id", "team_id"))  // 순서 다름
                .referencedTable("Team")
                .referencedColumns(List.of("dept_id", "id"))  // 순서 다름
                .build();

        CaseNormalizer normalizer = CaseNormalizer.lower();
        RelationshipKey key1 = RelationshipKey.of(rel1, normalizer);
        RelationshipKey key2 = RelationshipKey.of(rel2, normalizer);

        assertEquals(key1, key2);
    }

    @Test
    @DisplayName("참조 테이블이 다르면 다른 키를 생성해야 함")
    void shouldGenerateDifferentKeyForDifferentReferencedTable() {
        RelationshipModel rel1 = RelationshipModel.builder()
                .tableName("User")
                .columns(List.of("team_id"))
                .referencedTable("Team")
                .referencedColumns(List.of("id"))
                .build();

        RelationshipModel rel2 = RelationshipModel.builder()
                .tableName("User")
                .columns(List.of("team_id"))
                .referencedTable("Department")  // 다른 참조 테이블
                .referencedColumns(List.of("id"))
                .build();

        CaseNormalizer normalizer = CaseNormalizer.lower();
        RelationshipKey key1 = RelationshipKey.of(rel1, normalizer);
        RelationshipKey key2 = RelationshipKey.of(rel2, normalizer);

        assertNotEquals(key1, key2);
    }

    @Test
    @DisplayName("타입이 다른 관계는 같은 키를 생성해야 함 (유연성 유지)")
    void shouldGenerateSameKeyForDifferentRelationshipType() {
        RelationshipModel rel1 = RelationshipModel.builder()
                .type(RelationshipType.MANY_TO_ONE)
                .tableName("User")
                .columns(List.of("team_id"))
                .referencedTable("Team")
                .referencedColumns(List.of("id"))
                .build();

        RelationshipModel rel2 = RelationshipModel.builder()
                .type(RelationshipType.ONE_TO_ONE)  // 다른 타입
                .tableName("User")
                .columns(List.of("team_id"))
                .referencedTable("Team")
                .referencedColumns(List.of("id"))
                .build();

        CaseNormalizer normalizer = CaseNormalizer.lower();
        RelationshipKey key1 = RelationshipKey.of(rel1, normalizer);
        RelationshipKey key2 = RelationshipKey.of(rel2, normalizer);

        assertEquals(key1, key2, "타입이 달라도 같은 키를 생성해야 함 (유연성 유지)");
    }

    @Test
    @DisplayName("다른 CaseNormalizer를 사용하면 다른 결과를 얻을 수 있음")
    void shouldWorkWithDifferentCaseNormalizers() {
        RelationshipModel rel = RelationshipModel.builder()
                .tableName("User")
                .columns(List.of("team_id"))
                .referencedTable("Team")
                .referencedColumns(List.of("id"))
                .build();

        RelationshipKey lowerKey = RelationshipKey.of(rel, CaseNormalizer.lower());
        RelationshipKey upperKey = RelationshipKey.of(rel, CaseNormalizer.upper());
        RelationshipKey preserveKey = RelationshipKey.of(rel, CaseNormalizer.preserve());

        // 모두 다른 키를 생성해야 함
        assertNotEquals(lowerKey, upperKey);
        assertNotEquals(lowerKey, preserveKey);
        assertNotEquals(upperKey, preserveKey);
    }
}