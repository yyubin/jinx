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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@AutoService(Processor.class)
@SupportedAnnotationTypes({
        "jakarta.persistence.Entity",
        "jakarta.persistence.Table",
        "jakarta.persistence.Column",
        "jakarta.persistence.Id",
        "jakarta.persistence.Index",
        "jakarta.persistence.ManyToOne",
        "jakarta.persistence.JoinColumn",
        "jakarta.persistence.JoinTable",
        "jakarta.persistence.ManyToMany",
        "jakarta.persistence.OneToOne",
        "jakarta.persistence.MapsId",
        "jakarta.persistence.Embedded",
        "jakarta.persistence.Embeddable",
        "jakarta.persistence.Inheritance",
        "jakarta.persistence.DiscriminatorColumn",
        "jakarta.persistence.SequenceGenerator",
        "jakarta.persistence.SequenceGenerators",
        "jakarta.persistence.TableGenerator",
        "jakarta.persistence.Transient",
        "jakarta.persistence.Enumerated",
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
        this.entityHandler = new EntityHandler(context, columnHandler, embeddedHandler, constraintHandler, sequenceHandler);
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (Element element : roundEnv.getElementsAnnotatedWith(Entity.class)) {
            if (element.getKind() == ElementKind.CLASS) {
                entityHandler.handle((TypeElement) element);
            }
        }

        if (roundEnv.processingOver()) {
            // 1. 상속 해석 먼저 수행
            for (EntityModel entityModel : context.getSchemaModel().getEntities().values()) {
                if (!entityModel.isValid()) continue; // 유효하지 않은 엔티티 건너뛰기
                TypeElement typeElement = context.getElementUtils().getTypeElement(entityModel.getEntityName());
                inheritanceHandler.resolveInheritance(typeElement, entityModel);
            }
            // 2. 관계 해석 수행
            for (EntityModel entityModel : context.getSchemaModel().getEntities().values()) {
                if (!entityModel.isValid()) continue; // 유효하지 않은 엔티티 건너뛰기
                TypeElement typeElement = context.getElementUtils().getTypeElement(entityModel.getEntityName());
                relationshipHandler.resolveRelationships(typeElement, entityModel);
            }
            context.saveModelToJson();
        }
        return true;
    }
}