package org.jinx.migration;

public interface SqlContributor {
    int priority();
    void contribute(StringBuilder sb, Dialect dialect);
}
