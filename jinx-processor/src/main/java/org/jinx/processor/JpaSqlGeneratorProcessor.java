package org.jinx.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.auto.service.AutoService;
import jakarta.persistence.*;
import org.jinx.annotation.*;
import org.jinx.context.ProcessingContext;
import org.jinx.handler.*;
import org.jinx.model.*;
import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@AutoService(Processor.class)
@SupportedAnnotationTypes({
        "jakarta.persistence.*",
        "org.jinx.annotation.Constraint",
        "org.jinx.annotation.Constraints",
        "org.jinx.annotation.Identity"
})
@SupportedSourceVersion(SourceVersion.RELEASE_17)
public class JpaSqlGeneratorProcessor extends AbstractProcessor {

    public static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private ProcessingContext context;
    private EntityHandler entityHandler;
    private RelationshipHandler relationshipHandler;
    private InheritanceHandler inheritanceHandler;
    private SequenceHandler sequenceHandler;
    private EmbeddedHandler embeddedHandler;
    private ConstraintHandler constraintHandler;
    private ElementCollectionHandler elementCollectionHandler;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        SchemaModel schemaModel = SchemaModel.builder()
                .version(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")))
                .build();
        this.context = new ProcessingContext(processingEnv, schemaModel);

        this.sequenceHandler = new SequenceHandler(context);
        ColumnHandler columnHandler = new ColumnHandler(context, sequenceHandler);
        this.relationshipHandler = new RelationshipHandler(context);
        this.inheritanceHandler = new InheritanceHandler(context);
        this.embeddedHandler = new EmbeddedHandler(context, columnHandler);
        this.constraintHandler = new ConstraintHandler(context);
        this.elementCollectionHandler = new ElementCollectionHandler(context, columnHandler, embeddedHandler);
        this.entityHandler = new EntityHandler(context, columnHandler, embeddedHandler, constraintHandler, sequenceHandler, elementCollectionHandler);
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        // Process @Converter with autoApply=true
        for (Element element : roundEnv.getElementsAnnotatedWith(Converter.class)) {
            if (element.getKind() == ElementKind.CLASS) {
                Converter converter = element.getAnnotation(Converter.class);
                if (converter.autoApply()) {
                    TypeElement converterType = (TypeElement) element;
                    // Extract target Java type from AttributeConverter interface
                    TypeMirror superType = converterType.getInterfaces().stream()
                            .filter(i -> i.toString().startsWith("jakarta.persistence.AttributeConverter"))
                            .findFirst().orElse(null);
                    if (superType instanceof DeclaredType declaredType) {
                        TypeMirror targetType = declaredType.getTypeArguments().get(0);
                        context.getAutoApplyConverters().put(targetType.toString(), converterType.getQualifiedName().toString());
                    }
                }
            }
        }
        // Process @MappedSuperclass and @Embeddable first
        for (Element element : roundEnv.getElementsAnnotatedWith(MappedSuperclass.class)) {
            if (element.getKind() == ElementKind.CLASS) {
                TypeElement typeElement = (TypeElement) element;
                String qualifiedName = typeElement.getQualifiedName().toString();

                // Populate transient map for processing logic
                context.getSchemaModel().getProcessingMappedSuperclasses().put(qualifiedName, typeElement);

                // Populate DTO map for JSON serialization
                ClassInfoModel classInfo = new ClassInfoModel(qualifiedName);
                context.getSchemaModel().getMappedSuperclasses().put(qualifiedName, classInfo);
            }
        }
        for (Element element : roundEnv.getElementsAnnotatedWith(Embeddable.class)) {
            if (element.getKind() == ElementKind.CLASS) {
                TypeElement typeElement = (TypeElement) element;
                String qualifiedName = typeElement.getQualifiedName().toString();

                // Populate transient map for processing logic
                context.getSchemaModel().getProcessingEmbeddables().put(qualifiedName, typeElement);

                // Populate DTO map for JSON serialization
                ClassInfoModel classInfo = new ClassInfoModel(qualifiedName);
                context.getSchemaModel().getEmbeddables().put(qualifiedName, classInfo);
            }
        }

        // Process @Entity
        for (Element element : roundEnv.getElementsAnnotatedWith(Entity.class)) {
            if (element.getKind() == ElementKind.CLASS) {
                entityHandler.handle((TypeElement) element);
            }
        }

        if (roundEnv.processingOver()) {
            // 1. 상속 해석
            for (EntityModel entityModel : context.getSchemaModel().getEntities().values()) {
                if (!entityModel.isValid()) continue;
                TypeElement typeElement = context.getElementUtils().getTypeElement(entityModel.getEntityName());
                inheritanceHandler.resolveInheritance(typeElement, entityModel);
            }
            // 2. 관계 해석
            for (EntityModel entityModel : context.getSchemaModel().getEntities().values()) {
                if (!entityModel.isValid()) continue;
                TypeElement typeElement = context.getElementUtils().getTypeElement(entityModel.getEntityName());
                relationshipHandler.resolveRelationships(typeElement, entityModel);
            }
            context.saveModelToJson();
        }
        return true;
    }
}