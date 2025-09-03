package org.jinx.testing.mother;

import org.jinx.descriptor.AttributeDescriptor;
import org.mockito.ArgumentMatchers;

import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;

import java.lang.annotation.Annotation;
import java.util.List;

import static org.mockito.Mockito.*;

public final class AttributeDescriptorFactory {
    public static AttributeDescriptor setOf(String elemJavaType, String name, Annotation... anns) {
        AttributeDescriptor ad = mock(AttributeDescriptor.class);
        lenient().when(ad.name()).thenReturn(name);

        // 타입 모킹
        DeclaredType declared = mock(DeclaredType.class);
        TypeMirror elem = mock(TypeMirror.class);
        lenient().when(elem.toString()).thenReturn(elemJavaType);
        lenient().doReturn(List.of(elem)).when(declared).getTypeArguments();
        lenient().when(ad.type()).thenReturn(declared);

        // getAnnotation(Class<A>) 응답 로직 (★ 중요한 부분)
        when(ad.getAnnotation(ArgumentMatchers.<Class<? extends Annotation>>any()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    Class<? extends Annotation> cls = (Class<? extends Annotation>) inv.getArgument(0);
                    for (Annotation a : anns) {
                        if (cls.isInstance(a)) return a;
                    }
                    return null;
                });

        // hasAnnotation(Class<?>)
        lenient().when(ad.hasAnnotation(ArgumentMatchers.<Class<? extends Annotation>>any()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    Class<? extends Annotation> cls = (Class<? extends Annotation>) inv.getArgument(0);
                    for (Annotation a : anns) {
                        if (cls.isInstance(a)) return true;
                    }
                    return false;
                });

        return ad;
    }
    
    public static AttributeDescriptor listOf(String elementJavaType, String name, Annotation... anns) {
        AttributeDescriptor ad = setOf(elementJavaType, name, anns);
        // List 타입을 구분하기 위한 추가 처리는 테스트에서 context.isSubtype 모킹으로 처리
        return ad;
    }
    
    public static AttributeDescriptor mapOf(String keyJavaType, String valueJavaType, String name, Annotation... anns) {
        AttributeDescriptor ad = mock(AttributeDescriptor.class);
        when(ad.name()).thenReturn(name);
        DeclaredType mapType = mock(DeclaredType.class);
        TypeMirror keyType = mock(TypeMirror.class);
        TypeMirror valueType = mock(TypeMirror.class);
        when(keyType.toString()).thenReturn(keyJavaType);
        when(valueType.toString()).thenReturn(valueJavaType);
        doReturn(List.of(keyType, valueType)).when(mapType).getTypeArguments();
        when(ad.type()).thenReturn(mapType);
        // Annotation 모킹 공통 적용
        when(ad.getAnnotation(
                ArgumentMatchers.<Class<? extends Annotation>>any()
        )).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Class<? extends Annotation> clazz =
                    (Class<? extends Annotation>) inv.getArgument(0);
            return null; // 필요 시 찾은 어노테이션 리턴
        });
        return ad;
    }
    
    /**
     * 진단용 Element를 포함한 AttributeDescriptor 생성
     */
    public static AttributeDescriptor withDiagnostic(AttributeDescriptor base, VariableElement diagnostic) {
        lenient().when(base.elementForDiagnostics()).thenReturn(diagnostic);
        return base;
    }
    
    @SuppressWarnings("unchecked")
    private static <A extends Annotation> A findAnn(Annotation[] arr, Class<A> type) {
        for (Annotation a : arr) if (type.isInstance(a)) return (A) a;
        return null;
    }
}