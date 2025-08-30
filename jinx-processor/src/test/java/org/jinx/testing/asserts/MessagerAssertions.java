package org.jinx.testing.asserts;

import javax.annotation.processing.Messager;
import javax.tools.Diagnostic;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;

public final class MessagerAssertions {
    private MessagerAssertions(){}

    public static void assertErrorContains(Messager messager, String contains) {
        verify(messager).printMessage(
                eq(Diagnostic.Kind.ERROR),
                contains(contains),
                any() // element
        );
    }
}
