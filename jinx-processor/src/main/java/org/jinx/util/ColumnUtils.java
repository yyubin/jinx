package org.jinx.util;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;

public class ColumnUtils {
    public static String[] getEnumConstants(TypeMirror tm) {
        if (!(tm instanceof DeclaredType dt)) return new String[]{};
        Element e = dt.asElement();
        if (e.getKind() != ElementKind.ENUM) return new String[]{};
        return e.getEnclosedElements().stream()
                .filter(el -> el.getKind() == ElementKind.ENUM_CONSTANT)
                .map(Element::getSimpleName)
                .map(Object::toString)
                .toArray(String[]::new);
    }

}
