package org.jinx.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.auto.service.AutoService;
import jakarta.persistence.*;
import org.jinx.context.ProcessingContext;
import org.jinx.handler.*;
import org.jinx.model.ClassInfoModel;
import org.jinx.model.EntityModel;
import org.jinx.model.SchemaModel;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
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
    private TableGeneratorHandler tableGeneratorHandler;

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
        this.embeddedHandler = new EmbeddedHandler(context, columnHandler, relationshipHandler);
        this.constraintHandler = new ConstraintHandler(context);
        this.elementCollectionHandler = new ElementCollectionHandler(context, columnHandler, embeddedHandler);
        this.tableGeneratorHandler = new TableGeneratorHandler(context);
        this.entityHandler = new EntityHandler(context, columnHandler, embeddedHandler, constraintHandler, sequenceHandler, elementCollectionHandler, tableGeneratorHandler, relationshipHandler);
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        processRetryTasks();

        // Process @Converter with autoApply=true
        for (Element element : roundEnv.getElementsAnnotatedWith(Converter.class)) {
            if (element.getKind() == ElementKind.CLASS) {
                Converter converter = element.getAnnotation(Converter.class);
                if (converter.autoApply()) {
                    TypeElement converterType = (TypeElement) element;

                    Optional<TypeMirror> attrTypeOpt = findAttributeConverterAttributeType(converterType);

                    if (attrTypeOpt.isPresent()) {
                        String targetTypeName = attrTypeOpt.get().toString();
                        context.getAutoApplyConverters().put(targetTypeName, converterType.getQualifiedName().toString());
                    } else {
                        processingEnv.getMessager().printMessage(
                                Diagnostic.Kind.WARNING,
                                "@Converter(autoApply=true) cannot resolve AttributeConverter<T, ?> target type across hierarchy: "
                                        + converterType.getQualifiedName(),
                                converterType
                        );
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
            // Relationships are now processed during entity handling via AttributeDescriptor

            // 3. 최종 PK 검증 (2차 패스)
            for (Map.Entry<String, EntityModel> e : context.getSchemaModel().getEntities().entrySet()) {
                EntityModel em = e.getValue();
                if (!em.isValid()) continue;
                if (context.findAllPrimaryKeyColumns(em).isEmpty()) {
                    // FQN을 사용하여 TypeElement 조회
                    TypeElement te = context.getElementUtils().getTypeElement(e.getKey());
                    if (te != null) {
                        context.getMessager().printMessage(
                                Diagnostic.Kind.ERROR,
                                "Entity '" + e.getKey() + "' must have a primary key.",
                                te
                        );
                    } else {
                        context.getMessager().printMessage(
                                Diagnostic.Kind.ERROR,
                                "Entity '" + e.getKey() + "' must have a primary key."
                        );
                    }
                    em.setValid(false);
                }
            }

            // 4. Deferred FK (JOINED 상속 관련) 처리
            // 최대 5회 시도
            int maxPass = 5;
            for (int pass = 0; pass < maxPass && !context.getDeferredEntities().isEmpty(); pass++) {
                entityHandler.runDeferredJoinedFks();
            }
            if (!context.getDeferredEntities().isEmpty()) {
                context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "Unresolved JOINED inheritance: " + context.getDeferredEntities());
            }
            context.saveModelToJson();
        }
        return true;
    }

    public void processRetryTasks() {
        entityHandler.runDeferredJoinedFks();

    }

    private Optional<TypeMirror> findAttributeConverterAttributeType(TypeElement converterType) {
        Types types = processingEnv.getTypeUtils();
        Elements elements = processingEnv.getElementUtils();

        TypeElement acElement = elements.getTypeElement("jakarta.persistence.AttributeConverter");
        if (acElement == null) return Optional.empty();
        TypeMirror acErasure = types.erasure(acElement.asType());

        Deque<TypeMirror> q = new ArrayDeque<>();
        TypeMirror root = converterType != null ? converterType.asType() : null;
        if (root != null) q.add(root);

        while (!q.isEmpty()) {
            TypeMirror cur = q.poll();
            if (!(cur instanceof DeclaredType dt)) continue;

            if (types.isSameType(types.erasure(dt), acErasure)) {
                List<? extends TypeMirror> args = dt.getTypeArguments();
                if (args.size() == 2) return Optional.of(args.get(0));
            }

            Element el = dt.asElement();
            if (el instanceof TypeElement te) {
                for (TypeMirror itf : te.getInterfaces()) q.add(itf);
                TypeMirror sc = te.getSuperclass();
                if (sc != null && sc.getKind() != TypeKind.NONE) q.add(sc);
            }
        }
        return Optional.empty();
    }
}