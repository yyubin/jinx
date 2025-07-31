package org.jinx.migration.differs;

import org.jinx.model.DiffResult;
import org.jinx.model.SchemaModel;
import org.jinx.model.TableGeneratorModel;

import java.util.Optional;

public class TableGeneratorDiffer implements Differ {
    @Override
    public void diff(SchemaModel oldSchema, SchemaModel newSchema, DiffResult result) {
        newSchema.getTableGenerators().forEach((name, tg) -> {
            if (!oldSchema.getTableGenerators().containsKey(name)) {
                result.getTableGeneratorDiffs().add(DiffResult.TableGeneratorDiff.added(tg));
            } else if (!isTableGeneratorEqual(oldSchema.getTableGenerators().get(name), tg)) {
                String detail = getTableGeneratorChangeDetail(oldSchema.getTableGenerators().get(name), tg);

                result.getTableGeneratorDiffs().add(
                        DiffResult.TableGeneratorDiff.builder()
                                .type(DiffResult.TableGeneratorDiff.Type.MODIFIED)
                                .oldTableGenerator(oldSchema.getTableGenerators().get(name))
                                .tableGenerator(tg)
                                .changeDetail(detail)
                                .build()
                );
            }
        });
        oldSchema.getTableGenerators().forEach((name, tg) -> {
            if (!newSchema.getTableGenerators().containsKey(name)) {
                result.getTableGeneratorDiffs().add(DiffResult.TableGeneratorDiff.dropped(tg));
            }
        });
    }

    private boolean isTableGeneratorEqual(TableGeneratorModel oldTg, TableGeneratorModel newTg) {
        return Optional.ofNullable(oldTg.getTable()).equals(Optional.ofNullable(newTg.getTable())) &&
                Optional.ofNullable(oldTg.getSchema()).equals(Optional.ofNullable(newTg.getSchema())) &&
                Optional.ofNullable(oldTg.getCatalog()).equals(Optional.ofNullable(newTg.getCatalog())) &&
                Optional.ofNullable(oldTg.getPkColumnName()).equals(Optional.ofNullable(newTg.getPkColumnName())) &&
                Optional.ofNullable(oldTg.getValueColumnName()).equals(Optional.ofNullable(newTg.getValueColumnName())) &&
                Optional.ofNullable(oldTg.getPkColumnValue()).equals(Optional.ofNullable(newTg.getPkColumnValue())) &&
                oldTg.getInitialValue() == newTg.getInitialValue() &&
                oldTg.getAllocationSize() == newTg.getAllocationSize();
    }

    private String getTableGeneratorChangeDetail(TableGeneratorModel oldTg, TableGeneratorModel newTg) {
        StringBuilder detail = new StringBuilder();
        if (!Optional.ofNullable(oldTg.getTable()).equals(Optional.ofNullable(newTg.getTable()))) {
            detail.append("table changed from ").append(oldTg.getTable()).append(" to ").append(newTg.getTable()).append("; ");
        }
        if (!Optional.ofNullable(oldTg.getSchema()).equals(Optional.ofNullable(newTg.getSchema()))) {
            detail.append("schema changed from ").append(oldTg.getSchema()).append(" to ").append(newTg.getSchema()).append("; ");
        }
        if (!Optional.ofNullable(oldTg.getCatalog()).equals(Optional.ofNullable(newTg.getCatalog()))) {
            detail.append("catalog changed from ").append(oldTg.getCatalog()).append(" to ").append(newTg.getCatalog()).append("; ");
        }
        if (!Optional.ofNullable(oldTg.getPkColumnName()).equals(Optional.ofNullable(newTg.getPkColumnName()))) {
            detail.append("pkColumnName changed from ").append(oldTg.getPkColumnName()).append(" to ").append(newTg.getPkColumnName()).append("; ");
        }
        if (!Optional.ofNullable(oldTg.getValueColumnName()).equals(Optional.ofNullable(newTg.getValueColumnName()))) {
            detail.append("valueColumnName changed from ").append(oldTg.getValueColumnName()).append(" to ").append(newTg.getValueColumnName()).append("; ");
        }
        if (!Optional.ofNullable(oldTg.getPkColumnValue()).equals(Optional.ofNullable(newTg.getPkColumnValue()))) {
            detail.append("pkColumnValue changed from ").append(oldTg.getPkColumnValue()).append(" to ").append(newTg.getPkColumnValue()).append("; ");
        }
        if (oldTg.getInitialValue() != newTg.getInitialValue()) {
            detail.append("initialValue changed from ").append(oldTg.getInitialValue()).append(" to ").append(newTg.getInitialValue()).append("; ");
        }
        if (oldTg.getAllocationSize() != newTg.getAllocationSize()) {
            detail.append("allocationSize changed from ").append(oldTg.getAllocationSize()).append(" to ").append(newTg.getAllocationSize()).append("; ");
        }
        if (detail.length() > 2) {
            detail.setLength(detail.length() - 2);
        }
        return detail.toString();
    }
}