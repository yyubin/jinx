package org.jinx.handler;

import org.jinx.descriptor.AttributeDescriptor;
import org.jinx.model.ColumnModel;

import javax.lang.model.type.TypeMirror;
import java.util.Map;

/**
 * New interface for resolving columns from AttributeDescriptor
 */
public interface AttributeColumnResolver {
    ColumnModel resolve(AttributeDescriptor attribute, TypeMirror typeHint, String columnName, Map<String, String> overrides);
}