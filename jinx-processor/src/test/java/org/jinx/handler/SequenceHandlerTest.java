package org.jinx.handler;

import jakarta.persistence.SequenceGenerator;
import org.jinx.context.ProcessingContext;
import org.jinx.model.SchemaModel;
import org.jinx.model.SequenceModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;

import static com.google.common.truth.Truth.assertThat;

class SequenceHandlerTest {

    private ProcessingContext ctx;
    private SequenceHandler handler;
    private Element dummyElement;

    @BeforeEach
    void setUp() {
        ProcessingEnvironment env = Mockito.mock(ProcessingEnvironment.class);
        Messager messager = Mockito.mock(Messager.class);
        Mockito.when(env.getMessager()).thenReturn(messager);

        ctx = new ProcessingContext(env, SchemaModel.builder().build());
        handler = new SequenceHandler(ctx);
        dummyElement = Mockito.mock(Element.class);
    }

    @SequenceGenerator(
            name = "seq_valid",
            sequenceName = "seq_valid_db",
            initialValue = 5,
            allocationSize = 20,
            schema = "public",
            catalog = "cat")
    private static class Valid {}

    @SequenceGenerator(name = "seq_valid") // duplicate name
    private static class Duplicate {}

    @SequenceGenerator(name = "")          // blank name
    private static class Blank {}

    @Test
    void addsNewSequenceToSchema() {
        SequenceGenerator sg = Valid.class.getAnnotation(SequenceGenerator.class);

        handler.processSingleGenerator(sg, dummyElement);

        SequenceModel model = ctx.getSchemaModel().getSequences().get("seq_valid");
        assertThat(model).isNotNull();
        assertThat(model.getName()).isEqualTo("seq_valid_db");
        assertThat(model.getInitialValue()).isEqualTo(5);
        assertThat(model.getAllocationSize()).isEqualTo(20);
        assertThat(model.getSchema()).isEqualTo("public");
        assertThat(model.getCatalog()).isEqualTo("cat");
    }

    @Test
    void ignoresDuplicateSequenceName() {
        SequenceGenerator first = Valid.class.getAnnotation(SequenceGenerator.class);
        SequenceGenerator dup   = Duplicate.class.getAnnotation(SequenceGenerator.class);

        handler.processSingleGenerator(first, dummyElement);
        handler.processSingleGenerator(dup,   dummyElement);

        assertThat(ctx.getSchemaModel().getSequences()).hasSize(1);
    }

    @Test
    void blankNameIsRejected() {
        SequenceGenerator blank = Blank.class.getAnnotation(SequenceGenerator.class);

        handler.processSingleGenerator(blank, dummyElement);

        assertThat(ctx.getSchemaModel().getSequences()).isEmpty();
    }
}
