package org.jinx.context;

import lombok.Getter;
import org.jinx.descriptor.AttributeDescriptor;
import org.jinx.descriptor.AttributeDescriptorFactory;
import org.jinx.manager.ConstraintManager;
import org.jinx.model.ColumnModel;
import org.jinx.model.EntityModel;
import org.jinx.model.SchemaModel;
import org.jinx.naming.DefaultNaming;
import org.jinx.naming.Naming;
import org.jinx.options.JinxOptions;
import org.jinx.config.ConfigurationLoader;
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

/**
 * Holds the shared state and environment for a single annotation processing run.
 * <p>
 * This class provides access to the {@link ProcessingEnvironment}, the global {@link SchemaModel},
 * configuration settings, and various caches and registries that are valid for the duration
 * of one processing round.
 */
@Getter
public class ProcessingContext {
    private final ProcessingEnvironment processingEnv;
    private final SchemaModel schemaModel;
    private final Map<String, String> autoApplyConverters = new HashMap<>();
    private final Naming naming;
    private final Queue<EntityModel> deferredEntities = new ArrayDeque<>();
    private final Set<String> deferredNames = new HashSet<>();
    private final AttributeDescriptorFactory attributeDescriptorFactory;

    private final ConstraintManager constraintManager = new ConstraintManager(this);

    // TypeElement registry, valid only for the current processing round.
    private final Map<String, TypeElement> mappedSuperclassElements = new HashMap<>();
    private final Map<String, TypeElement> embeddableElements = new HashMap<>();
    
    // AttributeDescriptor caching to avoid re-computation during bidirectional relationship resolution
    private final Map<String, List<AttributeDescriptor>> descriptorCache = new HashMap<>();
    
    // MappedBy cycle detection: (ownerType, attributeName) -> inverse visited set
    private final Set<String> mappedByVisitedSet = new HashSet<>();

    // Map<entityFqcn, Map<pkAttrPath, List<columnName>>>
    private final Map<String, Map<String, List<String>>> pkAttributeToColumnMap = new HashMap<>();

    public ProcessingContext(ProcessingEnvironment processingEnv, SchemaModel schemaModel) {
        this.processingEnv = processingEnv;
        this.schemaModel = schemaModel;

        // Load configuration (with profile support).
        Map<String, String> config = loadConfiguration(processingEnv);
        int maxLength = parseMaxLength(config);

        this.naming = new DefaultNaming(maxLength);
        this.attributeDescriptorFactory = new AttributeDescriptorFactory(processingEnv.getTypeUtils(), processingEnv.getElementUtils(), this);
    }

    /**
     * Loads configuration, considering profiles.
     * Priority: -A options > configuration file > default values.
     */
    private Map<String, String> loadConfiguration(ProcessingEnvironment processingEnv) {
        // Determine profile: -Ajinx.profile > JINX_PROFILE environment variable > dev.
        String profile = processingEnv.getOptions().get(JinxOptions.Profile.PROCESSOR_KEY);

        ConfigurationLoader loader = new ConfigurationLoader();
        Map<String, String> config = loader.loadConfiguration(profile);

        // If values are explicitly specified with -A options, they override the config file.
        String explicitMaxLength = processingEnv.getOptions().get(JinxOptions.Naming.MAX_LENGTH_KEY);
        if (explicitMaxLength != null) {
            Map<String, String> mutableConfig = new HashMap<>(config);
            mutableConfig.put(JinxOptions.Naming.MAX_LENGTH_KEY, explicitMaxLength);
            return mutableConfig;
        }

        return config;
    }

    /**
     * Parses the maxLength value from the configuration.
     */
    private int parseMaxLength(Map<String, String> config) {
        String maxLenOpt = config.get(JinxOptions.Naming.MAX_LENGTH_KEY);
        int maxLength = JinxOptions.Naming.MAX_LENGTH_DEFAULT;

        if (maxLenOpt != null) {
            try {
                maxLength = Integer.parseInt(maxLenOpt);
            } catch (NumberFormatException e) {
                getMessager().printMessage(Diagnostic.Kind.WARNING,
                        "Invalid " + JinxOptions.Naming.MAX_LENGTH_KEY + ": " + maxLenOpt + " (use default " + maxLength + ")");
            }
        }

        return maxLength;
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
        System.out.println("Schema JSON written.");
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
        String fqn = typeElement.getQualifiedName().toString();
        return descriptorCache.computeIfAbsent(fqn,
                k -> attributeDescriptorFactory.createDescriptors(typeElement));
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

    public void unmarkMappedByVisited(String entityName, String attr) {
        mappedByVisitedSet.remove(entityName + "." + attr);
    }
    
    /**
     * Clear the mappedBy visited set (typically called when starting a new entity processing round).
     */
    public void clearMappedByVisited() {
        mappedByVisitedSet.clear();
    }

    /**
     * Registers the mapping from a primary key attribute path to its database columns.
     * This is used for @MapsId("...") resolution.
     *
     * @param entityFqcn The fully qualified name of the entity.
     * @param attributePath The dot-separated path to the attribute within an @EmbeddedId.
     * @param columnNames The list of database column names for that attribute.
     */
    public void registerPkAttributeColumns(String entityFqcn, String attributePath, List<String> columnNames) {
        pkAttributeToColumnMap
            .computeIfAbsent(entityFqcn, k -> new HashMap<>())
            .put(attributePath, columnNames);
    }

    /**
     * Retrieves the database column names for a given primary key attribute path.
     *
     * @param entityFqcn The fully qualified name of the entity.
     * @param attributePath The attribute path to look up.
     * @return A list of column names, or null if not found.
     */
    public List<String> getPkColumnsForAttribute(String entityFqcn, String attributePath) {
        return Optional.ofNullable(pkAttributeToColumnMap.get(entityFqcn))
            .map(attrMap -> attrMap.get(attributePath))
            .orElse(null);
    }

    public void registerMappedSuperclassElement(String fqn, TypeElement el) {
        mappedSuperclassElements.put(fqn, el);
    }
    public void registerEmbeddableElement(String fqn, TypeElement el) {
        embeddableElements.put(fqn, el);
    }
    public TypeElement getMappedSuperclassElement(String fqn) {
        return mappedSuperclassElements.get(fqn);
    }
    public TypeElement getEmbeddableElement(String fqn) {
        return embeddableElements.get(fqn);
    }
    
    /**
     * Initialize context state at the beginning of an annotation processing round.
     * This prevents cross-round contamination of deferred queues and visit tracking.
     */
    public void beginRound() {
        clearMappedByVisited();
//        deferredEntities.clear();
//        deferredNames.clear();
        descriptorCache.clear();
        pkAttributeToColumnMap.clear();
        mappedSuperclassElements.clear();
        embeddableElements.clear();
    }
}