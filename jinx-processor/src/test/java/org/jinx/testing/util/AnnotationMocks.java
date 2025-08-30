package org.jinx.testing.util;

import jakarta.persistence.*;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.lang.annotation.Annotation;

import static org.mockito.Mockito.*;

public final class AnnotationMocks {
    private AnnotationMocks() {}

    public static JoinColumn joinColumn(String name, String referencedColumnName) {
        JoinColumn jc = mock(JoinColumn.class);
        lenient().when(jc.name()).thenReturn(name);
        lenient().when(jc.referencedColumnName()).thenReturn(referencedColumnName);
        // 필요없는 속성은 기본값
        lenient().when(jc.nullable()).thenReturn(true);
        lenient().when(jc.unique()).thenReturn(false);
        return jc;
    }

    public static CollectionTable collectionTable(String name, JoinColumn... joinColumns) {
        CollectionTable ct = mock(CollectionTable.class);
        when(ct.name()).thenReturn(name);
        when(ct.joinColumns()).thenReturn(joinColumns);
        return ct;
    }

    public static OrderColumn orderColumn(String name) {
        OrderColumn oc = mock(OrderColumn.class);
        when(oc.name()).thenReturn(name);
        return oc;
    }

    /** Annotation 배열을 AttributeDescriptorFactory 등에서 class 기준으로 매칭하는 데 쓸 수 있는 헬퍼 */
    @SuppressWarnings("unchecked")
    public static <A extends Annotation> A matchByClass(Annotation[] anns, Class<A> cls) {
        for (Annotation a : anns) if (cls.isInstance(a)) return (A) a;
        return null;
    }

    /** Mockito any() 제네릭 경계를 올리는 헬퍼 (Class<?> → Class<? extends Annotation>) */
    public static <T extends Annotation> Class<T> anyAnnClass() {
        return ArgumentMatchers.<Class<T>>any();
    }
}
