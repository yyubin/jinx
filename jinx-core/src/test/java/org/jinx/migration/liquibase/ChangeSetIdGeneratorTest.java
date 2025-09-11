package org.jinx.migration.liquibase;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChangeSetIdGeneratorTest {

    @Test
    @DisplayName("nextId는 타임스탬프-번호 형식으로 증가한다")
    void nextId_incrementsSequence() {
        ChangeSetIdGenerator gen = new ChangeSetIdGenerator();

        String id1 = gen.nextId();
        String id2 = gen.nextId();

        // 공통 prefix (yyyyMMddHHmmss-)
        String prefix = id1.substring(0, 14); // yyyyMMddHHmmss
        assertTrue(id1.startsWith(prefix));
        assertTrue(id2.startsWith(prefix));

        // 시퀀스가 증가해야 함
        assertTrue(id1.endsWith("-1"));
        assertTrue(id2.endsWith("-2"));
    }
}
