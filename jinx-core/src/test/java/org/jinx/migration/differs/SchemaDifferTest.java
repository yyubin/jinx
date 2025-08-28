package org.jinx.migration.differs;

import org.jinx.model.DiffResult;
import org.jinx.model.SchemaModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.*;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;

class SchemaDifferTest {

    @Mock
    private TableDiffer tableDiffer;
    @Mock
    private EntityModificationDiffer entityModificationDiffer;
    @Mock
    private SequenceDiffer sequenceDiffer;
    @Mock
    private TableGeneratorDiffer tableGeneratorDiffer;

    @InjectMocks
    private SchemaDiffer schemaDiffer;

    private SchemaModel oldSchema;
    private SchemaModel newSchema;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        schemaDiffer = new SchemaDiffer();

        try {
            Field differsField = SchemaDiffer.class.getDeclaredField("differs");
            differsField.setAccessible(true);
            List<Differ> mockDiffers = Arrays.asList(tableDiffer, entityModificationDiffer, sequenceDiffer, tableGeneratorDiffer);
            differsField.set(schemaDiffer, mockDiffers);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            fail("Failed to inject mock differs using reflection.", e);
        }

        oldSchema = Mockito.mock(SchemaModel.class);
        newSchema = Mockito.mock(SchemaModel.class);
    }

    @Test
    @DisplayName("diff should call all delegated differs in the correct order")
    void diff_should_call_all_delegated_differs() {
        DiffResult result = schemaDiffer.diff(oldSchema, newSchema);

        assertNotNull(result, "The returned DiffResult should not be null.");

        InOrder inOrder = inOrder(tableDiffer, entityModificationDiffer, sequenceDiffer, tableGeneratorDiffer);

        inOrder.verify(tableDiffer).diff(eq(oldSchema), eq(newSchema), any(DiffResult.class));
        inOrder.verify(entityModificationDiffer).diff(eq(oldSchema), eq(newSchema), any(DiffResult.class));
        inOrder.verify(sequenceDiffer).diff(eq(oldSchema), eq(newSchema), any(DiffResult.class));
        inOrder.verify(tableGeneratorDiffer).diff(eq(oldSchema), eq(newSchema), any(DiffResult.class));
    }

    @Test
    @DisplayName("diff should return a valid DiffResult even with empty schemas")
    void diff_with_empty_schemas() {
        SchemaModel emptyOldSchema = Mockito.mock(SchemaModel.class);
        SchemaModel emptyNewSchema = Mockito.mock(SchemaModel.class);

        DiffResult result = schemaDiffer.diff(emptyOldSchema, emptyNewSchema);

        assertNotNull(result);

        verify(tableDiffer).diff(any(), any(), any());
        verify(entityModificationDiffer).diff(any(), any(), any());
        verify(sequenceDiffer).diff(any(), any(), any());
        verify(tableGeneratorDiffer).diff(any(), any(), any());
    }
}
