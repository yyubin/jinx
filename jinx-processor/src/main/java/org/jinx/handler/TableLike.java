package org.jinx.handler;

import org.jinx.model.ConstraintModel;
import org.jinx.model.IndexModel;

import java.util.List;
import java.util.Optional;

public interface TableLike {
    String getName();
    Optional<String> getSchema();
    Optional<String> getCatalog();
    Optional<String> getComment();

    List<ConstraintModel> getConstraints();
    List<IndexModel> getIndexes();

}
