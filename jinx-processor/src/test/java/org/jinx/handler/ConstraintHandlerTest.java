package org.jinx.handler;

import jakarta.persistence.Column;
import org.jinx.annotation.Constraint;
import org.jinx.annotation.Constraints;
import org.jinx.context.ProcessingContext;
import org.jinx.model.ConstraintModel;
import org.jinx.model.ConstraintType;
import org.jinx.model.OnDeleteAction;
import org.jinx.model.OnUpdateAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.VariableElement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConstraintHandlerTest {

    @Mock
    private ProcessingContext context;
    @Mock
    private VariableElement fieldElement; // Using VariableElement as it's a common use case

    private ConstraintHandler constraintHandler;
    private List<ConstraintModel> constraintsList;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        constraintHandler = new ConstraintHandler(context);
        constraintsList = new ArrayList<>();
        // Default mock behavior for the element
        when(fieldElement.getKind()).thenReturn(ElementKind.FIELD);
    }

    @Test
    @DisplayName("Should process a single @Constraint of type CHECK correctly")
    void processConstraints_WithSingleCheckConstraint_AddsConstraintModel() {
        // Given
        Constraint constraint = mock(Constraint.class);
        when(constraint.value()).thenReturn("chk_age");
        when(constraint.type()).thenReturn(ConstraintType.CHECK);
        when(constraint.expression()).thenReturn("age > 18");
        when(fieldElement.getAnnotation(Constraint.class)).thenReturn(constraint);

        // When
        constraintHandler.processConstraints(fieldElement, "age", constraintsList, "users");

        // Then
        assertThat(constraintsList).hasSize(1);
        ConstraintModel model = constraintsList.get(0);
        assertThat(model.getName()).isEqualTo("chk_age");
        assertThat(model.getType()).isEqualTo(ConstraintType.CHECK);
        assertThat(model.getCheckClause().get()).isEqualTo("age > 18");
        assertThat(model.getTableName()).isEqualTo("users");
        assertThat(model.getColumns()).containsExactly("age");
    }

    @Test
    @DisplayName("Should process multiple constraints using @Constraints")
    void processConstraints_WithMultipleConstraints_AddsAllConstraintModels() {
        // Given
        Constraint constraint1 = mock(Constraint.class);
        when(constraint1.value()).thenReturn("uk_email");
        when(constraint1.type()).thenReturn(ConstraintType.UNIQUE);

        Constraint constraint2 = mock(Constraint.class);
        when(constraint2.value()).thenReturn("chk_email_format");
        when(constraint2.type()).thenReturn(ConstraintType.CHECK);
        when(constraint2.expression()).thenReturn("email LIKE '%@%'");

        Constraints constraintsAnnotation = mock(Constraints.class);
        when(constraintsAnnotation.value()).thenReturn(new Constraint[]{constraint1, constraint2});
        when(fieldElement.getAnnotation(Constraints.class)).thenReturn(constraintsAnnotation);

        // When
        constraintHandler.processConstraints(fieldElement, "email", constraintsList, "users");

        // Then
        assertThat(constraintsList).hasSize(2);
        ConstraintModel uniqueModel = constraintsList.stream().filter(c -> c.getType() == ConstraintType.UNIQUE).findFirst().orElse(null);
        ConstraintModel checkModel = constraintsList.stream().filter(c -> c.getType() == ConstraintType.CHECK).findFirst().orElse(null);

        assertThat(uniqueModel).isNotNull();
        assertThat(uniqueModel.getName()).isEqualTo("uk_email");

        assertThat(checkModel).isNotNull();
        assertThat(checkModel.getName()).isEqualTo("chk_email_format");
        assertThat(checkModel.getCheckClause().get()).isEqualTo("email LIKE '%@%'");
    }

    @Test
    @DisplayName("Should infer UNIQUE type when type is AUTO and @Column(unique=true) is present")
    void processConstraints_WithAutoTypeAndUniqueColumn_InfersUniqueConstraint() {
        // Given
        Constraint constraint = mock(Constraint.class);
        when(constraint.value()).thenReturn("auto_uk_username");
        when(constraint.type()).thenReturn(ConstraintType.AUTO);

        Column column = mock(Column.class);
        when(column.unique()).thenReturn(true);

        when(fieldElement.getAnnotation(Constraint.class)).thenReturn(constraint);
        when(fieldElement.getAnnotation(Column.class)).thenReturn(column);

        // When
        constraintHandler.processConstraints(fieldElement, "username", constraintsList, "profiles");

        // Then
        assertThat(constraintsList).hasSize(1);
        ConstraintModel model = constraintsList.get(0);
        assertThat(model.getName()).isEqualTo("auto_uk_username");
        assertThat(model.getType()).isEqualTo(ConstraintType.UNIQUE);
    }

    @Test
    @DisplayName("Should do nothing if AUTO type cannot be inferred")
    void processConstraints_WithAutoTypeAndNoInference_DoesNothing() {
        // Given
        Constraint constraint = mock(Constraint.class);
        when(constraint.value()).thenReturn("auto_constraint");
        when(constraint.type()).thenReturn(ConstraintType.AUTO);
        // No @Column(unique=true) is present
        when(fieldElement.getAnnotation(Constraint.class)).thenReturn(constraint);

        // When
        constraintHandler.processConstraints(fieldElement, "some_field", constraintsList, "some_table");

        // Then
        assertThat(constraintsList).isEmpty();
    }

    @Test
    @DisplayName("Should correctly set OnDelete and OnUpdate actions")
    void processConstraints_WithOnDeleteAndOnUpdate_SetsActions() {
        // Given
        Constraint constraint = mock(Constraint.class);
        when(constraint.value()).thenReturn("fk_test");
        when(constraint.type()).thenReturn(ConstraintType.UNIQUE); // Using UNIQUE as FK is disabled
        when(constraint.onDelete()).thenReturn(OnDeleteAction.CASCADE);
        when(constraint.onUpdate()).thenReturn(OnUpdateAction.SET_NULL);
        when(fieldElement.getAnnotation(Constraint.class)).thenReturn(constraint);

        // When
        constraintHandler.processConstraints(fieldElement, "user_id", constraintsList, "orders");

        // Then
        assertThat(constraintsList).hasSize(1);
        ConstraintModel model = constraintsList.get(0);
        assertThat(model.getOnDelete()).isEqualTo(OnDeleteAction.CASCADE);
        assertThat(model.getOnUpdate()).isEqualTo(OnUpdateAction.SET_NULL);
    }

    @Test
    @DisplayName("Should ignore CHECK constraint if expression is blank")
    void processConstraints_WithBlankCheckExpression_IgnoresConstraint() {
        // Given
        Constraint constraint = mock(Constraint.class);
        when(constraint.value()).thenReturn("chk_invalid");
        when(constraint.type()).thenReturn(ConstraintType.CHECK);
        when(constraint.expression()).thenReturn("   "); // Blank expression
        when(fieldElement.getAnnotation(Constraint.class)).thenReturn(constraint);

        // When
        constraintHandler.processConstraints(fieldElement, "field", constraintsList, "table");

        // Then
        assertThat(constraintsList).isEmpty();
    }

    @Test
    @DisplayName("Should do nothing if no constraint annotations are present")
    void processConstraints_WithNoAnnotations_DoesNothing() {
        // Given
        // No annotations are mocked on fieldElement

        // When
        constraintHandler.processConstraints(fieldElement, "field", constraintsList, "table");

        // Then
        assertThat(constraintsList).isEmpty();
    }
}
