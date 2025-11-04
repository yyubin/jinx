package org.jinx.handler;

import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.SequenceGenerators;
import org.jinx.context.ProcessingContext;
import org.jinx.model.SequenceModel;

import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Handles the processing of {@link SequenceGenerator} annotations.
 * <p>
 * This handler discovers {@code @SequenceGenerator} and {@code @SequenceGenerators}
 * annotations at the class level and registers them as {@link SequenceModel} instances
 * in the global schema model.
 */
public class SequenceHandler {
    private final ProcessingContext context;

    public SequenceHandler(ProcessingContext context) {
        this.context = context;
    }

    /**
     * Processes class-level {@code @SequenceGenerator} and {@code @SequenceGenerators} annotations.
     *
     * @param typeElement The TypeElement of the class to process.
     */
    public void processSequenceGenerators(TypeElement typeElement) {
        List<SequenceGenerator> sequenceGenerators = new ArrayList<>();
        SequenceGenerator single = typeElement.getAnnotation(SequenceGenerator.class);
        if (single != null) sequenceGenerators.add(single);
        SequenceGenerators multiple = typeElement.getAnnotation(SequenceGenerators.class);
        if (multiple != null) sequenceGenerators.addAll(Arrays.asList(multiple.value()));

        for (SequenceGenerator sg : sequenceGenerators) {
            processSingleGenerator(sg, typeElement);
        }
    }

    /**
     * Common method to process a single {@code @SequenceGenerator} annotation.
     *
     * @param sg The SequenceGenerator annotation instance.
     * @param element The element on which the annotation was found, for error reporting.
     */
    public void processSingleGenerator(SequenceGenerator sg, Element element) {
        if (sg.name().isBlank()) {
            context.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                    "SequenceGenerator must have a non-blank name.", element);
            return;
        }
        if (context.getSchemaModel().getSequences().containsKey(sg.name())) {
            // Ignore if already registered.
            return;
        }
        context.getSchemaModel().getSequences().computeIfAbsent(sg.name(), key ->
                SequenceModel.builder()
                        .name(sg.sequenceName().isBlank() ? key : sg.sequenceName())
                        .initialValue(sg.initialValue())
                        .schema(sg.schema().isEmpty() ? null : sg.schema())
                        .catalog(sg.catalog().isEmpty() ? null : sg.catalog())
                        .allocationSize(sg.allocationSize())
                        .build()
        );
    }
}