package org.jinx.migration;

import org.jinx.migration.dialect.mysql.MySqlVisitorProvider;
import org.jinx.migration.spi.VisitorProvider;
import org.jinx.model.DialectBundle;
import org.jinx.model.VisitorProviders;

import java.util.List;

public final class VisitorFactory {
    private static final List<VisitorProvider> PROVIDERS = List.of(
            new MySqlVisitorProvider()
    );

    public static VisitorProviders forBundle(DialectBundle bundle) {
        return PROVIDERS.stream()
                .filter(provider -> provider.supports(bundle))
                .findFirst()
                .map(provider -> provider.create(bundle))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unsupported database type: " + bundle.databaseType()
                ));
    }
}
