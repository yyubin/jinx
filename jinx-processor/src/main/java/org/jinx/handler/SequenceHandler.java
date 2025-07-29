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

public class SequenceHandler {
    private final ProcessingContext context;

    public SequenceHandler(ProcessingContext context) {
        this.context = context;
    }

    // 클래스 레벨의 @SequenceGenerator와 @SequenceGenerators 처리
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

    // 단일 @SequenceGenerator 처리 공통 메서드
    public void processSingleGenerator(SequenceGenerator sg, Element element) {
        if (sg.name().isBlank()) {
            context.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                    "SequenceGenerator must have a non-blank name.", element);
            return;
        }
        if (context.getSchemaModel().getSequences().containsKey(sg.name())) {
            // 중복 시 조용히 넘어갈까..?
            return;
        }
        context.getSchemaModel().getSequences().computeIfAbsent(sg.name(), key ->
                SequenceModel.builder()
                        .name(sg.sequenceName().isBlank() ? key : sg.sequenceName())
                        .initialValue(sg.initialValue())
                        .allocationSize(sg.allocationSize())
                        .build()
        );
    }
}