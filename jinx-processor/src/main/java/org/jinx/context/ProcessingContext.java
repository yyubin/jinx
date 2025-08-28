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

    /**
     * Create a ProcessingContext for annotation processing of JINX models.
     *
     * <p>Stores the provided processing environment and schema model, initializes the naming
     * strategy from the processor option {@code jinx.naming.maxLength} (defaults to 30 if
     * missing or invalid), and constructs the AttributeDescriptorFactory used to build
     * attribute descriptors and the descriptor cache.</p>
     *
     * <p>If the {@code jinx.naming.maxLength} option is present but cannot be parsed as an
     * integer, a warning is reported via the processing Messager and the default length (30)
     * is used.</p>
     *
     * @param schemaModel the SchemaModel being built/processed
     */
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

    /**
     * Determines whether the given type is a subtype of the named supertype.
     *
     * If the named supertype cannot be resolved in the processing environment, an error
     * message is emitted to the annotation processing Messager and the method returns false.
     *
     * @param type the type to test
     * @param supertypeName the fully qualified name of the supertype to check against
     * @return true if {@code type} is a subtype of the resolved {@code supertypeName}; false otherwise
     */
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
     * Return attribute descriptors for the given type element, creating and caching them on first access.
     *
     * The descriptors describe the persistent/processable attributes of the provided type and are cached
     * to avoid re-computation during bidirectional relationship resolution.
     *
     * @param typeElement the type element whose attribute descriptors are requested
     * @return a non-null list of AttributeDescriptor instances for the given type (may be empty)
     */
    public List<AttributeDescriptor> getCachedDescriptors(TypeElement typeElement) {
        return descriptorCache.computeIfAbsent(typeElement, 
                te -> attributeDescriptorFactory.createDescriptors(te));
    }
    
    /**
     * Returns true if the mappedBy relationship identified by the given owner entity and attribute has already been visited.
     *
     * The membership key is formed as "ownerEntityName.attributeName". This is used to prevent infinite recursion when resolving bidirectional relationships.
     *
     * @param ownerEntityName the owner entity's name
     * @param attributeName the attribute name on the owner entity
     * @return true if the mappedBy relationship has been marked visited
     */
    public boolean isMappedByVisited(String ownerEntityName, String attributeName) {
        String key = ownerEntityName + "." + attributeName;
        return mappedByVisitedSet.contains(key);
    }
    
    /**
     * Marks a bidirectional `mappedBy` relationship as visited to prevent infinite recursion when resolving relationships.
     *
     * The relationship is recorded as "ownerEntityName.attributeName" in the internal visited set.
     *
     * @param ownerEntityName the name of the owning entity
     * @param attributeName the name of the attribute on the owner entity that refers to the inverse side
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
}