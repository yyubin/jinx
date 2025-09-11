package org.jinx.migration.liquibase;

import org.jinx.migration.liquibase.model.Change;
import org.jinx.migration.liquibase.model.ChangeSet;
import org.jinx.migration.liquibase.model.ChangeSetWrapper;
import org.jinx.migration.liquibase.model.Constraints;
import org.jinx.model.ColumnModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LiquibaseUtilsTest {

    static class DummyChange implements Change {}

    @Test
    @DisplayName("createChangeSet: id/author/changes가 그대로 들어간다")
    void createChangeSet_buildsWrapper() {
        Change dummy = new DummyChange();

        ChangeSetWrapper wrapper = LiquibaseUtils.createChangeSet("abc123", List.of(dummy));

        assertNotNull(wrapper);
        ChangeSet cs = wrapper.getChangeSet();
        assertEquals("abc123", cs.getId());
        assertEquals("auto-generated", cs.getAuthor());
        assertEquals(1, cs.getChanges().size());
        assertSame(dummy, cs.getChanges().get(0));
    }

    @Test
    @DisplayName("buildConstraintsWithoutPK: 컬럼이 NOT NULL(false)일 때만 nullable=false가 설정되고, 그 외에는 null")
    void buildConstraintsWithoutPk_nullableBehavior() {
        // ColumnModel.isNullable() 이 primitive boolean 이라고 가정
        ColumnModel notNullCol = mock(ColumnModel.class);
        when(notNullCol.isNullable()).thenReturn(false);

        Constraints c1 = LiquibaseUtils.buildConstraintsWithoutPK(notNullCol, "users");
        assertNotNull(c1);
        assertEquals(Boolean.FALSE, c1.getNullable()); // 명시적으로 false 설정

        ColumnModel nullableCol = mock(ColumnModel.class);
        when(nullableCol.isNullable()).thenReturn(true);

        Constraints c2 = LiquibaseUtils.buildConstraintsWithoutPK(nullableCol, "users");
        assertNotNull(c2);
        assertNull(c2.getNullable()); // 설정 안 함 → null
    }
}
