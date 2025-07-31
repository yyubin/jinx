package org.jinx.migration.differs;

import org.jinx.model.DiffResult;
import org.jinx.model.SchemaModel;
import org.jinx.model.SequenceModel;

import java.util.Objects;
import java.util.Optional;

public class SequenceDiffer implements Differ {
    @Override
    public void diff(SchemaModel oldSchema, SchemaModel newSchema, DiffResult result) {
        newSchema.getSequences().forEach((name, seq) -> {
            if (!oldSchema.getSequences().containsKey(name)) {
                result.getSequenceDiffs().add(DiffResult.SequenceDiff.added(seq));
            } else if (!isSequenceEqual(oldSchema.getSequences().get(name), seq)) {
                String detail = getSequenceChangeDetail(oldSchema.getSequences().get(name), seq);
                result.getSequenceDiffs().add(
                        DiffResult.SequenceDiff.builder()
                                .type(DiffResult.SequenceDiff.Type.MODIFIED)
                                .oldSequence(oldSchema.getSequences().get(name))
                                .sequence(seq)
                                .changeDetail(detail)
                                .build());
            }
        });
        oldSchema.getSequences().forEach((name, seq) -> {
            if (!newSchema.getSequences().containsKey(name)) {
                result.getSequenceDiffs().add(DiffResult.SequenceDiff.dropped(seq));
            }
        });
    }

    private boolean isSequenceEqual(SequenceModel oldSeq, SequenceModel newSeq) {
        return oldSeq.getInitialValue() == newSeq.getInitialValue() &&
                oldSeq.getAllocationSize() == newSeq.getAllocationSize() &&
                oldSeq.getCache() == newSeq.getCache() &&
                oldSeq.getMinValue() == newSeq.getMinValue() &&
                oldSeq.getMaxValue() == newSeq.getMaxValue() &&
                Optional.ofNullable(oldSeq.getSchema()).equals(Optional.ofNullable(newSeq.getSchema())) &&
                Optional.ofNullable(oldSeq.getCatalog()).equals(Optional.ofNullable(newSeq.getCatalog()));
    }

    private String getSequenceChangeDetail(SequenceModel oldSeq, SequenceModel newSeq) {
        StringBuilder detail = new StringBuilder();
        if (oldSeq.getInitialValue() != newSeq.getInitialValue()) {
            detail.append("initialValue changed from ").append(oldSeq.getInitialValue()).append(" to ").append(newSeq.getInitialValue()).append("; ");
        }
        if (oldSeq.getAllocationSize() != newSeq.getAllocationSize()) {
            detail.append("allocationSize changed from ").append(oldSeq.getAllocationSize()).append(" to ").append(newSeq.getAllocationSize()).append("; ");
        }
        if (oldSeq.getCache() != newSeq.getCache()) {
            detail.append("cache changed from ").append(oldSeq.getCache()).append(" to ").append(newSeq.getCache()).append("; ");
        }
        if (oldSeq.getMinValue() != newSeq.getMinValue()) {
            detail.append("minValue changed from ").append(oldSeq.getMinValue()).append(" to ").append(newSeq.getMinValue()).append("; ");
        }
        if (oldSeq.getMaxValue() != newSeq.getMaxValue()) {
            detail.append("maxValue changed from ").append(oldSeq.getMaxValue()).append(" to ").append(newSeq.getMaxValue()).append("; ");
        }
        if (!Objects.equals(oldSeq.getSchema(), newSeq.getSchema())) {
            detail.append("schema changed from ").append(oldSeq.getSchema()).append(" to ").append(newSeq.getSchema()).append("; ");
        }
        if (!Objects.equals(oldSeq.getCatalog(), newSeq.getCatalog())) {
            detail.append("catalog changed from ").append(oldSeq.getCatalog()).append(" to ").append(newSeq.getCatalog()).append("; ");
        }
        if (detail.length() > 2) {
            detail.setLength(detail.length() - 2);
        }
        return detail.toString();
    }
}