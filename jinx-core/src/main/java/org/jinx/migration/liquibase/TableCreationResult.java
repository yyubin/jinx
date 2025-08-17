package org.jinx.migration.liquibase;

import org.jinx.migration.liquibase.model.ChangeSetWrapper;

import java.util.List;

public record TableCreationResult(
        List<ChangeSetWrapper> createTableChangeSets,
        List<ChangeSetWrapper> createIndexChangeSets,
        List<ChangeSetWrapper> addFkChangeSets
) {}