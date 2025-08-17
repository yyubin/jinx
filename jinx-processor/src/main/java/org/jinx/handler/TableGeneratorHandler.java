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

public class TableGeneratorHandler {
    private final ProcessingContext context;

    public TableGeneratorHandler(ProcessingContext context) {
        this.context = context;
    }

    // 클래스 레벨의 @TableGenerator와 @TableGenerators 처리
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

    // 단일 @TableGenerator 처리 공통 메서드
    public void processSingleGenerator(TableGenerator tg, Element element) {
        if (tg.name().isBlank()) {
            context.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                    "TableGenerator must have a non-blank name.", element);
            return;
        }
        if (context.getSchemaModel().getTableGenerators().containsKey(tg.name())) {
            return;  // 중복 시 무시
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