package org.jinx.testing.visitor;

import org.jinx.model.DiffResult;
import org.jinx.model.VisitorProviders;
import org.jinx.migration.spi.visitor.TableContentVisitor;
import org.jinx.migration.spi.visitor.TableVisitor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

public class RecordingVisitors {

    public final List<RecordingTableVisitor> tableVisitors = new ArrayList<>();
    public final List<RecordingTableContentVisitor> contentVisitors = new ArrayList<>();

    public VisitorProviders providers(boolean includeSequence, boolean includeTableGenerator) {
        Supplier<TableVisitor> tvSup = () -> {
            RecordingTableVisitor v = new RecordingTableVisitor();
            tableVisitors.add(v);
            return v;
        };
        Function<DiffResult.ModifiedEntity, TableContentVisitor> tcvFun = me -> {
            RecordingTableContentVisitor v = new RecordingTableContentVisitor();
            contentVisitors.add(v);
            return v;
        };

        Optional<Supplier<org.jinx.migration.spi.visitor.SequenceVisitor>> seq =
                includeSequence ? Optional.of(RecordingSequenceVisitor::new) : Optional.empty();

        Optional<Supplier<org.jinx.migration.spi.visitor.TableGeneratorVisitor>> tg =
                includeTableGenerator ? Optional.of(RecordingTableGeneratorVisitor::new) : Optional.empty();

        return new VisitorProviders(tvSup, tcvFun, seq, tg);
    }
}
