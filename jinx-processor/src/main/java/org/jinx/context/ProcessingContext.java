package org.jinx.context;

import lombok.Getter;
import org.jinx.model.ColumnModel;
import org.jinx.model.ConstraintType;
import org.jinx.model.EntityModel;
import org.jinx.model.SchemaModel;
import org.jinx.processor.JpaSqlGeneratorProcessor;

import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Getter
public class ProcessingContext {
    private final ProcessingEnvironment processingEnv;
    private final SchemaModel schemaModel;
    private final Map<String, String> autoApplyConverters = new HashMap<>();
    private final Naming naming;
    private final Queue<EntityModel> deferredEntities = new ArrayDeque<>();
    private final Set<String> deferredNames = new HashSet<>();

    public ProcessingContext(ProcessingEnvironment processingEnv, SchemaModel schemaModel) {
        this.processingEnv = processingEnv;
        this.schemaModel = schemaModel;
        String maxLenOpt = processingEnv.getOptions().getOrDefault("jinx.naming.maxLength", "30");
        int maxLength = 30;
        try { maxLength = Integer.parseInt(maxLenOpt); }
        catch (NumberFormatException e) {
            getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "Invalid jinx.naming.maxLength: " + maxLenOpt + " (use default " + maxLength + ")");
        }
        this.naming = new DefaultNaming(maxLength);
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
        if (schemaModel.getEntities().isEmpty()) {
            return;
        }
        if (schemaModel.getVersion() == null || schemaModel.getVersion().isEmpty()) {
            schemaModel.setVersion(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")));
        }
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

    public List<ColumnModel> findAllPrimaryKeyColumns(EntityModel entityModel) {
        return entityModel.getColumns().values().stream()
                .filter(ColumnModel::isPrimaryKey)
                .toList();
    }

    public boolean isSubtype(TypeMirror type, String supertypeName) {
        Elements elements = getElementUtils();
        Types types = getTypeUtils();
        javax.lang.model.element.TypeElement supertypeElement = elements.getTypeElement(supertypeName);
        if (supertypeElement == null) {
            getMessager().printMessage(Diagnostic.Kind.ERROR, "Supertype not found: " + supertypeName);
            return false;
        }
        TypeMirror supertypeMirror = supertypeElement.asType();
        return types.isSubtype(type, supertypeMirror);
    }
}