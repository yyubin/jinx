package org.jinx.context;

import lombok.Getter;
import org.jinx.model.ColumnModel;
import org.jinx.model.EntityModel;
import org.jinx.model.SchemaModel;
import org.jinx.processor.JpaSqlGeneratorProcessor;

import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Getter
public class ProcessingContext {
    private final ProcessingEnvironment processingEnv;
    private final SchemaModel schemaModel;
    private final Map<String, String> autoApplyConverters = new HashMap<>();

    public ProcessingContext(ProcessingEnvironment processingEnv, SchemaModel schemaModel) {
        this.processingEnv = processingEnv;
        this.schemaModel = schemaModel;
    }


    public Messager getMessager() {
        return processingEnv.getMessager();
    }

    public Elements getElementUtils() {
        return processingEnv.getElementUtils();
    }

    public Types getTypeUtils() {
        return processingEnv.getTypeUtils();
    }

    public void saveModelToJson() {
        try {
            String fileName = "jinx/schema-" + schemaModel.getVersion() + ".json";
            FileObject file = processingEnv.getFiler()
                    .createResource(StandardLocation.CLASS_OUTPUT, "", fileName);
            try (Writer writer = file.openWriter()) {
                JpaSqlGeneratorProcessor.OBJECT_MAPPER.writeValue(writer, schemaModel);
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                    "Failed to write schema file: " + e.getMessage());
        }
    }

    public Optional<String> findPrimaryKeyColumnName(EntityModel entityModel) {
        return entityModel.getColumns().values().stream()
                .filter(ColumnModel::isPrimaryKey)
                .map(ColumnModel::getColumnName)
                .findFirst();
    }
}