package org.jinx.handler;

import org.jinx.context.ProcessingContext;
import org.jinx.handler.relationship.RelationshipProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class RelationshipHandlerConstructorTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    ProcessingContext context;

    @Test
    void constructor_buildsProcessors_and_sortsByOrder() throws Exception {
        RelationshipHandler handler = new RelationshipHandler(context);
        assertNotNull(handler);

        // processors 필드를 리플렉션으로 점검
        Field f = RelationshipHandler.class.getDeclaredField("processors");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<RelationshipProcessor> processors = (List<RelationshipProcessor>) f.get(handler);

        assertNotNull(processors);
        assertTrue(processors.size() >= 5, "expected at least 5 processors");

        // 중복 없는지
        Set<Class<?>> uniq = new HashSet<>();
        for (RelationshipProcessor p : processors) {
            assertTrue(uniq.add(p.getClass()), "duplicate processor class: " + p.getClass().getName());
        }

        // order()가 비내림차순(정렬)인지
        int prev = Integer.MIN_VALUE;
        for (RelationshipProcessor p : processors) {
            int cur = p.order();
            assertTrue(cur >= prev, "processors not sorted by order: " + prev + " -> " + cur);
            prev = cur;
        }
    }
}
