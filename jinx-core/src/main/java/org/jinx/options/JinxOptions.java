package org.jinx.options;

/**
 * Defines configuration option constants used throughout Jinx.
 * Ensures configuration consistency by using the same keys in both the CLI and the Annotation Processor.
 */
public final class JinxOptions {

    private JinxOptions() {
    }

    /**
     * Profile-related settings.
     */
    public static final class Profile {
        private Profile() {}

        /**
         * Default profile name.
         */
        public static final String DEFAULT = "dev";

        /**
         * Profile environment variable name.
         */
        public static final String ENV_VAR = "JINX_PROFILE";

        /**
         * Configuration file name.
         */
        public static final String CONFIG_FILE = "jinx.yaml";

        /**
         * Option key for specifying the profile in the Annotation Processor.
         */
        public static final String PROCESSOR_KEY = "jinx.profile";
    }

    /**
     * Naming-related settings.
     */
    public static final class Naming {
        private Naming() {}

        /**
         * Maximum length for generated constraint/index names.
         * Default: 30
         */
        public static final String MAX_LENGTH_KEY = "jinx.naming.maxLength";
        public static final int MAX_LENGTH_DEFAULT = 30;
    }

    // public static final class Database {
    //     public static final String URL_KEY = "jinx.database.url";
    //     public static final String USERNAME_KEY = "jinx.database.username";
    //     public static final String PASSWORD_KEY = "jinx.database.password";
    // }

    // public static final class Output {
    //     public static final String FORMAT_KEY = "jinx.output.format";
    //     public static final String FORMAT_DEFAULT = "sql";
    // }
}