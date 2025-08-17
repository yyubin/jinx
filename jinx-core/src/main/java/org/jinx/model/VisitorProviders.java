package org.jinx.model;

import org.jinx.migration.spi.visitor.SequenceVisitor;
import org.jinx.migration.spi.visitor.TableContentVisitor;
import org.jinx.migration.spi.visitor.TableGeneratorVisitor;
import org.jinx.migration.spi.visitor.TableVisitor;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

public record VisitorProviders(
        Supplier<TableVisitor> tableVisitor,
        Function<DiffResult.ModifiedEntity, TableContentVisitor> tableContentVisitor,
        Optional<Supplier<SequenceVisitor>> sequenceVisitor,
        Optional<Supplier<TableGeneratorVisitor>> tableGeneratorVisitor
) {}