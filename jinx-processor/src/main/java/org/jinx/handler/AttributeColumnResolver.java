package org.jinx.handler;

import org.jinx.descriptor.AttributeDescriptor;
import org.jinx.model.ColumnModel;

import javax.lang.model.type.TypeMirror;
import java.util.Map;

/**
 * New interface for resolving columns from AttributeDescriptor
 */
public interface AttributeColumnResolver {
    /**
 * Resolve a ColumnModel for the given attribute.
 *
 * The implementation determines the resulting column definition based on the
 * provided AttributeDescriptor, an optional type hint, an optional explicit
 * column name, and any configuration overrides.
 *
 * @param attribute   descriptor of the source attribute to resolve from
 * @param typeHint    optional type mirror to guide type-to-column mapping; may be null
 * @param columnName  explicit column name to use if present; may be null or empty
 * @param overrides   runtime overrides (key/value) that can alter mapping behavior; may be empty
 * @return            a ColumnModel describing the resolved column for the attribute
 */
ColumnModel resolve(AttributeDescriptor attribute, TypeMirror typeHint, String columnName, Map<String, String> overrides);
}