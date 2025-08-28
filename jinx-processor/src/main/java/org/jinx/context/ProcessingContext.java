package org.jinx.context;

import lombok.Getter;
import org.jinx.descriptor.AttributeDescriptor;
import org.jinx.descriptor.AttributeDescriptorFactory;
import org.jinx.model.ColumnModel;
import org.jinx.model.EntityModel;
import org.jinx.model.SchemaModel;
import org.jinx.processor.JpaSqlGeneratorProcessor;

import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.TypeElement;
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
    private final AttributeDescriptorFactory attributeDescriptorFactory;
    
    // AttributeDescriptor caching to avoid re-computation during bidirectional relationship resolution
    private final Map<TypeElement, List<AttributeDescriptor>> descriptorCache = new HashMap<>();
    
    // MappedBy cycle detection: (ownerType, attributeName) -> inverse visited set
    private final Set<String> mappedByVisitedSet = new HashSet<>();

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
        this.attributeDescriptorFactory = new AttributeDescriptorFactory(processingEnv.getTypeUtils(), processingEnv.getElementUtils(), this);
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
    
    /**
     * Get cached AttributeDescriptors for a TypeElement, creating and caching them if necessary.
     * This prevents re-computation during bidirectional relationship resolution.
     */
    public List<AttributeDescriptor> getCachedDescriptors(TypeElement typeElement) {
        return descriptorCache.computeIfAbsent(typeElement, 
                te -> attributeDescriptorFactory.createDescriptors(te));
    }
    
    /**
     * Check if a mappedBy relationship has been visited to prevent infinite recursion.
     * Key format: "ownerEntityName.attributeName"
     */
    public boolean isMappedByVisited(String ownerEntityName, String attributeName) {
        String key = ownerEntityName + "." + attributeName;
        return mappedByVisitedSet.contains(key);
    }
    
    /**
     * Mark a mappedBy relationship as visited to prevent cycles.
     */
    public void markMappedByVisited(String ownerEntityName, String attributeName) {
        String key = ownerEntityName + "." + attributeName;
        mappedByVisitedSet.add(key);
    }
    
    /**
     * Clear the mappedBy visited set (typically called when starting a new entity processing round).
     */
    public void clearMappedByVisited() {
        mappedByVisitedSet.clear();
    }
    
    /**
     * Initialize context state at the beginning of an annotation processing round.
     * This prevents cross-round contamination of deferred queues and visit tracking.
     */
    public void beginRound() {
        clearMappedByVisited();
        deferredEntities.clear();
        deferredNames.clear();
    }
}