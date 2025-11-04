package org.jinx.handler;

import jakarta.persistence.TableGenerator;
import jakarta.persistence.TableGenerators;
import org.jinx.context.ProcessingContext;
import org.jinx.model.TableGeneratorModel;

import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Handles the processing of {@link TableGenerator} annotations.
 * <p>
 * This handler discovers {@code @TableGenerator} and {@code @TableGenerators}
 * annotations at the class level and registers them as {@link TableGeneratorModel} instances
 * in the global schema model.
 */
public class TableGeneratorHandler {
    private final ProcessingContext context;

    public TableGeneratorHandler(ProcessingContext context) {
        this.context = context;
    }

    /**
     * Processes class-level {@code @TableGenerator} and {@code @TableGenerators} annotations.
     *
     * @param typeElement The TypeElement of the class to process.
     */
    public void processTableGenerators(TypeElement typeElement) {
        List<TableGenerator> tableGenerators = new ArrayList<>();
        TableGenerator single = typeElement.getAnnotation(TableGenerator.class);
        if (single != null) tableGenerators.add(single);
        TableGenerators multiple = typeElement.getAnnotation(TableGenerators.class);
        if (multiple != null) tableGenerators.addAll(Arrays.asList(multiple.value()));

        for (TableGenerator tg : tableGenerators) {
            processSingleGenerator(tg, typeElement);
        }
    }

    /**
     * Common method to process a single {@code @TableGenerator} annotation.
     *
     * @param tg The TableGenerator annotation instance.
     * @param element The element on which the annotation was found, for error reporting.
     */
    public void processSingleGenerator(TableGenerator tg, Element element) {
        if (tg.name().isBlank()) {
            context.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                    "TableGenerator must have a non-blank name.", element);
            return;
        }
        if (context.getSchemaModel().getTableGenerators().containsKey(tg.name())) {
            // Ignore if duplicate.
            return;
        }
        context.getSchemaModel().getTableGenerators().computeIfAbsent(tg.name(), key ->
                TableGeneratorModel.builder()
                        .name(key)
                        .table(tg.table().isBlank() ? key : tg.table())
                        .schema(tg.schema().isEmpty() ? null : tg.schema())
                        .catalog(tg.catalog().isEmpty() ? null : tg.catalog())
                        .pkColumnName(tg.pkColumnName().isBlank() ? "sequence_name" : tg.pkColumnName())
                        .pkColumnValue(tg.pkColumnValue().isBlank() ? key : tg.pkColumnValue())
                        .valueColumnName(tg.valueColumnName().isBlank() ? "next_val" : tg.valueColumnName())
                        .initialValue(tg.initialValue())
                        .allocationSize(tg.allocationSize())
                        .build()
        );
    }
}