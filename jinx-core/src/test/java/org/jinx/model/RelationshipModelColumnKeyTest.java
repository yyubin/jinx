package org.jinx.model;

import org.jinx.model.naming.CaseNormalizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RelationshipModelColumnKeyTest {

    private RelationshipModel relationshipModel;
    private CaseNormalizer lowerNormalizer;
    private CaseNormalizer upperNormalizer;

    @BeforeEach
    void setUp() {
        lowerNormalizer = CaseNormalizer.lower();
        upperNormalizer = CaseNormalizer.upper();
        
        relationshipModel = RelationshipModel.builder()
                .tableName("User")
                .columns(List.of("team_id", "dept_id"))
                .referencedTable("Team")
                .referencedColumns(List.of("id", "uuid"))
                .build();
    }

    @Test
    @DisplayName("getColumnsKeys는 정규화된 ColumnKey를 반환해야 함")
    void getColumnsKeysShouldReturnNormalizedColumnKeys() {
        List<ColumnKey> columnKeys = relationshipModel.getColumnsKeys(lowerNormalizer);
        
        // 정규화된 상태인지 확인
        assertEquals(2, columnKeys.size());
        
        // 첫 번째 컬럼 키 - canonical()을 통해 정규화 확인
        ColumnKey firstKey = columnKeys.get(0);
        assertEquals("user::team_id", firstKey.canonical()); // 정규화됨
        assertEquals("User::team_id", firstKey.display()); // 원본 보존
        
        // 두 번째 컬럼 키
        ColumnKey secondKey = columnKeys.get(1);
        assertEquals("user::dept_id", secondKey.canonical()); // 정규화됨
        assertEquals("User::dept_id", secondKey.display()); // 원본 보존
    }

    @Test
    @DisplayName("getReferencedColumnKeys는 정규화된 ColumnKey를 반환해야 함")
    void getReferencedColumnKeysShouldReturnNormalizedColumnKeys() {
        List<ColumnKey> columnKeys = relationshipModel.getReferencedColumnKeys(upperNormalizer);
        
        // 정규화된 상태인지 확인
        assertEquals(2, columnKeys.size());
        
        // 첫 번째 참조 컬럼 키
        ColumnKey firstKey = columnKeys.get(0);
        assertEquals("TEAM::ID", firstKey.canonical()); // 대문자 정규화됨
        assertEquals("Team::id", firstKey.display()); // 원본 보존
        
        // 두 번째 참조 컬럼 키
        ColumnKey secondKey = columnKeys.get(1);
        assertEquals("TEAM::UUID", secondKey.canonical()); // 대문자 정규화됨
        assertEquals("Team::uuid", secondKey.display()); // 원본 보존
    }

    @Test
    @DisplayName("반환되는 리스트는 수정 불가능해야 함")
    void returnedListsShouldBeUnmodifiable() {
        List<ColumnKey> columnKeys = relationshipModel.getColumnsKeys(lowerNormalizer);
        List<ColumnKey> referencedColumnKeys = relationshipModel.getReferencedColumnKeys(lowerNormalizer);
        
        // 수정 불가능한지 확인
        assertThrows(UnsupportedOperationException.class, () -> 
            columnKeys.add(ColumnKey.of("test", "test", lowerNormalizer))
        );
        
        assertThrows(UnsupportedOperationException.class, () -> 
            referencedColumnKeys.add(ColumnKey.of("test", "test", lowerNormalizer))
        );
        
        assertThrows(UnsupportedOperationException.class, () -> 
            columnKeys.clear()
        );
        
        assertThrows(UnsupportedOperationException.class, () -> 
            referencedColumnKeys.clear()
        );
    }

    @Test
    @DisplayName("컬럼 순서는 원본 순서를 유지해야 함")
    void shouldPreserveOriginalColumnOrder() {
        List<ColumnKey> columnKeys = relationshipModel.getColumnsKeys(lowerNormalizer);
        List<ColumnKey> referencedColumnKeys = relationshipModel.getReferencedColumnKeys(lowerNormalizer);
        
        // 원본 순서 유지 확인 - columns: ["team_id", "dept_id"]
        assertEquals("user::team_id", columnKeys.get(0).canonical());
        assertEquals("user::dept_id", columnKeys.get(1).canonical());
        
        // 원본 순서 유지 확인 - referencedColumns: ["id", "uuid"]
        assertEquals("team::id", referencedColumnKeys.get(0).canonical());
        assertEquals("team::uuid", referencedColumnKeys.get(1).canonical());
    }

    @Test
    @DisplayName("null 컬럼 리스트에 대해 빈 리스트를 반환해야 함")
    void shouldReturnEmptyListForNullColumns() {
        RelationshipModel nullColumnsModel = RelationshipModel.builder()
                .tableName("User")
                .columns(null)  // null
                .referencedTable("Team")
                .referencedColumns(null)  // null
                .build();
        
        List<ColumnKey> columnKeys = nullColumnsModel.getColumnsKeys(lowerNormalizer);
        List<ColumnKey> referencedColumnKeys = nullColumnsModel.getReferencedColumnKeys(lowerNormalizer);
        
        assertTrue(columnKeys.isEmpty());
        assertTrue(referencedColumnKeys.isEmpty());
        
        // 빈 리스트도 수정 불가능해야 함
        assertThrows(UnsupportedOperationException.class, () -> 
            columnKeys.add(ColumnKey.of("test", "test", lowerNormalizer))
        );
        assertThrows(UnsupportedOperationException.class, () -> 
            referencedColumnKeys.add(ColumnKey.of("test", "test", lowerNormalizer))
        );
    }

    @Test
    @DisplayName("대소문자가 다른 normalizer에 대해 일관된 정규화를 적용해야 함")
    void shouldApplyConsistentNormalizationForDifferentCaseNormalizers() {
        RelationshipModel mixedCaseModel = RelationshipModel.builder()
                .tableName("USER")  // 대문자
                .columns(List.of("TEAM_ID", "dept_id"))  // 대소문자 혼용
                .referencedTable("team")  // 소문자
                .referencedColumns(List.of("ID", "uuid"))  // 대소문자 혼용
                .build();
        
        // 소문자 정규화
        List<ColumnKey> lowerColumnKeys = mixedCaseModel.getColumnsKeys(lowerNormalizer);
        List<ColumnKey> lowerReferencedKeys = mixedCaseModel.getReferencedColumnKeys(lowerNormalizer);
        
        assertEquals("user::team_id", lowerColumnKeys.get(0).canonical());
        assertEquals("user::dept_id", lowerColumnKeys.get(1).canonical());
        assertEquals("team::id", lowerReferencedKeys.get(0).canonical());
        assertEquals("team::uuid", lowerReferencedKeys.get(1).canonical());
        
        // 대문자 정규화
        List<ColumnKey> upperColumnKeys = mixedCaseModel.getColumnsKeys(upperNormalizer);
        List<ColumnKey> upperReferencedKeys = mixedCaseModel.getReferencedColumnKeys(upperNormalizer);
        
        assertEquals("USER::TEAM_ID", upperColumnKeys.get(0).canonical());
        assertEquals("USER::DEPT_ID", upperColumnKeys.get(1).canonical());
        assertEquals("TEAM::ID", upperReferencedKeys.get(0).canonical());
        assertEquals("TEAM::UUID", upperReferencedKeys.get(1).canonical());
    }

    @Test
    @DisplayName("정규화된 ColumnKey들은 canonical 기반 equals가 작동해야 함")
    void normalizedColumnKeysShouldSupportCanonicalBasedEquals() {
        RelationshipModel model1 = RelationshipModel.builder()
                .tableName("USER")  // 대문자
                .columns(List.of("TEAM_ID"))
                .referencedTable("team")  // 소문자
                .referencedColumns(List.of("ID"))
                .build();
        
        RelationshipModel model2 = RelationshipModel.builder()
                .tableName("user")  // 소문자
                .columns(List.of("team_id"))
                .referencedTable("TEAM")  // 대문자
                .referencedColumns(List.of("id"))
                .build();
        
        // 같은 정규화기로 생성한 ColumnKey들은 동일해야 함
        List<ColumnKey> keys1 = model1.getColumnsKeys(lowerNormalizer);
        List<ColumnKey> keys2 = model2.getColumnsKeys(lowerNormalizer);
        List<ColumnKey> refKeys1 = model1.getReferencedColumnKeys(lowerNormalizer);
        List<ColumnKey> refKeys2 = model2.getReferencedColumnKeys(lowerNormalizer);
        
        assertEquals(keys1.get(0), keys2.get(0)); // canonical 기반 equals
        assertEquals(refKeys1.get(0), refKeys2.get(0)); // canonical 기반 equals
        
        // canonical 값도 동일해야 함
        assertEquals("user::team_id", keys1.get(0).canonical());
        assertEquals("user::team_id", keys2.get(0).canonical());
        assertEquals("team::id", refKeys1.get(0).canonical());
        assertEquals("team::id", refKeys2.get(0).canonical());
    }
}