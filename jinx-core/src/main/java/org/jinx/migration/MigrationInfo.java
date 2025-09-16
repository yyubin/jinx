package org.jinx.migration;

/**
 * Migration information for SQL headers
 */
public class MigrationInfo {
    private final String baselineHash;
    private final String headHash;
    private final String version;

    public MigrationInfo(String baselineHash, String headHash, String version) {
        this.baselineHash = baselineHash;
        this.headHash = headHash;
        this.version = version;
    }

    public String getBaselineHash() { return baselineHash; }
    public String getHeadHash() { return headHash; }
    public String getVersion() { return version; }
}