package org.jinx.handler;

import org.jinx.model.ColumnModel;

import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import java.util.Map;

public interface ColumnResolver {
    ColumnModel resolve(VariableElement field, TypeMirror typeHint, String columnName, Map<String, String> overrides);
}
