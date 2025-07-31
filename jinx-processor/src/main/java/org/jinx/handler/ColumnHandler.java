package org.jinx.handler;

import org.jinx.context.ProcessingContext;
import org.jinx.handler.builtins.CollectionElementResolver;
import org.jinx.handler.builtins.EntityFieldResolver;
import org.jinx.model.*;

import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import java.util.Map;

public class ColumnHandler {
    private final ProcessingContext context;
    private final SequenceHandler sequenceHandler;
    private final ColumnResolver fieldBasedResolver;
    private final ColumnResolver typeBasedResolver;

    public ColumnHandler(ProcessingContext context, SequenceHandler sequenceHandler) {
        this.context = context;
        this.sequenceHandler = sequenceHandler;
        this.fieldBasedResolver = new CollectionElementResolver(context);
        this.typeBasedResolver = new EntityFieldResolver(context, sequenceHandler);
    }

    public ColumnModel createFrom(VariableElement field, Map<String, String> overrides) {
        return typeBasedResolver.resolve(field, null, null, overrides);
    }

    public ColumnModel createFromFieldType(VariableElement field, TypeMirror type, String columnName) {
        return fieldBasedResolver.resolve(field, type, columnName, Map.of());
    }
}