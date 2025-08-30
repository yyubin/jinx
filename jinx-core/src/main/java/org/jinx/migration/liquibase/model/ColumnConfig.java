package org.jinx.migration.liquibase.model;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
public class ColumnConfig {
    private String name;
    private String type;
    
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String defaultValue;
    
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String defaultValueSequenceNext;
    
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String defaultValueComputed;
    
    private Constraints constraints;
    private Boolean autoIncrement;


    // ── 빌더 진입점 제공 ──
    public static ColumnConfigBuilder builder() {
        return new ColumnConfigBuilder();
    }

    public static class ColumnConfigBuilder {
        // ── 기본 필드도 빌더 상태로 유지 ──
        private String name;
        private String type;
        private Constraints constraints;
        private Boolean autoIncrement;
        
        // ── 상호배타적 기본값 필드들 ──
        private String defaultValue;
        private String defaultValueSequenceNext;
        private String defaultValueComputed;

        public ColumnConfigBuilder name(String name) {
            this.name = name;
            return this;
        }

        public ColumnConfigBuilder type(String type) {
            this.type = type;
            return this;
        }

        public ColumnConfigBuilder constraints(Constraints constraints) {
            this.constraints = constraints;
            return this;
        }

        public ColumnConfigBuilder autoIncrement(Boolean autoIncrement) {
            this.autoIncrement = autoIncrement;
            return this;
        }

        public ColumnConfigBuilder defaultValue(String defaultValue) {
            if (defaultValue != null) {
                if (shouldSkipSetting("defaultValue")) {
                    return this; // Skip setting if higher priority values exist
                }
                clearLowerPriorityValues("defaultValue");
            }
            this.defaultValue = defaultValue;
            return this;
        }

        public ColumnConfigBuilder defaultValueSequenceNext(String defaultValueSequenceNext) {
            if (defaultValueSequenceNext != null) {
                if (shouldSkipSetting("defaultValueSequenceNext")) {
                    return this; // Skip setting if higher priority values exist
                }
                clearLowerPriorityValues("defaultValueSequenceNext");
            }
            this.defaultValueSequenceNext = defaultValueSequenceNext;
            return this;
        }

        public ColumnConfigBuilder defaultValueComputed(String defaultValueComputed) {
            if (defaultValueComputed != null) {
                clearLowerPriorityValues("defaultValueComputed");
            }
            this.defaultValueComputed = defaultValueComputed;
            return this;
        }

        /**
         * Check if setting this field should be skipped due to higher priority fields already being set
         */
        private boolean shouldSkipSetting(String fieldName) {
            switch (fieldName) {
                case "defaultValue":
                    // Skip literal if computed or sequence is set
                    return this.defaultValueComputed != null || this.defaultValueSequenceNext != null;
                case "defaultValueSequenceNext":
                    // Skip sequence if computed is set
                    return this.defaultValueComputed != null;
                case "defaultValueComputed":
                    // Computed has highest priority, never skip
                    return false;
                default:
                    return false;
            }
        }

        /**
         * Clear lower priority values when setting a higher priority field
         */
        private void clearLowerPriorityValues(String fieldName) {
            boolean hasExistingValues = this.defaultValueComputed != null || 
                                       this.defaultValueSequenceNext != null || 
                                       this.defaultValue != null;

            if (hasExistingValues) {
                String warningMsg = String.format(
                    "[WARN] ColumnConfig: Setting %s will override existing default values. Priority: computed > sequence > literal.",
                    fieldName
                );
                System.err.println(warningMsg);
            }

            switch (fieldName) {
                case "defaultValueComputed":
                    // Computed clears both sequence and literal
                    this.defaultValueSequenceNext = null;
                    this.defaultValue = null;
                    break;
                case "defaultValueSequenceNext":
                    // Sequence clears only literal (computed already handled in shouldSkipSetting)
                    this.defaultValue = null;
                    break;
                case "defaultValue":
                    // Literal doesn't clear anything (higher priorities already handled in shouldSkipSetting)
                    break;
            }
        }

        public ColumnConfig build() {
            // Final validation before build
            int setCount = 0;
            if (defaultValueComputed != null) setCount++;
            if (defaultValueSequenceNext != null) setCount++;
            if (defaultValue != null) setCount++;

            if (setCount > 1) {
                throw new IllegalStateException(
                    "ColumnConfig: Multiple default value fields are set. Priority must enforce mutual exclusivity."
                );
            }

            ColumnConfig config = new ColumnConfig();
            config.setName(name);
            config.setType(type);
            config.setDefaultValue(defaultValue);
            config.setDefaultValueSequenceNext(defaultValueSequenceNext);
            config.setDefaultValueComputed(defaultValueComputed);
            config.setConstraints(constraints);
            config.setAutoIncrement(autoIncrement);
            return config;
        }
    }
}