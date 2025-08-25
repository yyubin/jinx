    /*
     * Additional tests appended by automation.
     * Note: Test framework: JUnit 5 (org.junit.jupiter.*) with Mockito (org.mockito.junit.jupiter.MockitoExtension).
     */

    @Test
    void testManyToOneRelationship_WithExplicitJoinColumn() {
        // Given
        VariableElement field = mockField("targetField");
        TypeElement ownerTypeElement = mockOwnerTypeElement(field);
        mockManyToOneAnnotation(field, false); // optional = false
        // Explicit FK name and referenced column
        mockJoinColumnAnnotation(field, "explicit_fk", "id", false);

        // Target has single PK (id: Long)
        ColumnModel targetPk = ColumnModel.builder()
                .columnName("id")
                .javaType("Long")
                .isPrimaryKey(true)
                .build();
        targetEntity.getColumns().put("id", targetPk);
        when(context.findAllPrimaryKeyColumns(targetEntity)).thenReturn(List.of(targetPk));

        TypeElement targetTypeElement = mockTypeElement("com.example.Target");
        mockFieldType(field, targetTypeElement);

        // When
        handler.resolveRelationships(ownerTypeElement, ownerEntity);

        // Then
        assertTrue(ownerEntity.getColumns().containsKey("explicit_fk"), "Explicit FK column should be created");
        ColumnModel fk = ownerEntity.getColumns().get("explicit_fk");
        assertEquals("Long", fk.getJavaType());
        assertFalse(fk.isNullable(), "Explicit nullable=false should result in NOT NULL FK");

        assertEquals(1, ownerEntity.getRelationships().size());
        RelationshipModel relationship = ownerEntity.getRelationships().values().iterator().next();
        assertEquals(List.of("explicit_fk"), relationship.getColumns());
        assertEquals("target_table", relationship.getReferencedTable());
        assertEquals(List.of("id"), relationship.getReferencedColumns());
    }

    @Test
    void testManyToOne_ExistingMatchingColumn_Reused_NoError() {
        // Given
        VariableElement field = mockField("targetField");
        TypeElement ownerTypeElement = mockOwnerTypeElement(field);
        mockManyToOneAnnotation(field, true); // optional = true

        // Existing FK column with matching type should be reused (no error)
        String expectedFkName = "targetField_id";
        ownerEntity.getColumns().put(expectedFkName, ColumnModel.builder()
                .columnName(expectedFkName)
                .javaType("Long")
                .build());

        ColumnModel targetPk = ColumnModel.builder()
                .columnName("id")
                .javaType("Long")
                .isPrimaryKey(true)
                .build();
        targetEntity.getColumns().put("id", targetPk);
        when(context.findAllPrimaryKeyColumns(targetEntity)).thenReturn(List.of(targetPk));

        TypeElement targetTypeElement = mockTypeElement("com.example.Target");
        mockFieldType(field, targetTypeElement);

        // When
        handler.resolveRelationships(ownerTypeElement, ownerEntity);

        // Then
        verify(messager, never()).printMessage(eq(Diagnostic.Kind.ERROR), anyString(), eq(field));
        assertFalse(ownerEntity.getRelationships().isEmpty(), "Relationship should be created");
        RelationshipModel relationship = ownerEntity.getRelationships().values().iterator().next();
        assertTrue(relationship.getColumns().contains(expectedFkName),
                "Existing matching FK column should be referenced by the relationship");
        // Ensure the reused column type remains intact
        assertEquals("Long", ownerEntity.getColumns().get(expectedFkName).getJavaType());
    }

    @Test
    void testManyToMany_NoJoinTableAnnotation_UsesDefaultNaming() {
        // Given
        VariableElement field = mockField("targets");
        TypeElement ownerTypeElement = mockOwnerTypeElement(field);
        mockManyToManyAnnotation(field); // No @JoinTable provided

        // Configure single-column PKs for both sides
        setupSinglePrimaryKeys();
        mockCollectionFieldType(field, "com.example.Target");

        // When
        handler.resolveRelationships(ownerTypeElement, ownerEntity);

        // Then
        String expectedJoinTable = namingStrategy.joinTableName(ownerEntity.getTableName(), targetEntity.getTableName());
        assertTrue(entities.containsKey(expectedJoinTable),
                "Default join table should be created when @JoinTable is absent");
        EntityModel joinTable = entities.get(expectedJoinTable);

        String ownerFkName = namingStrategy.foreignKeyColumnName(ownerEntity.getTableName(), "id");
        String targetFkName = namingStrategy.foreignKeyColumnName(targetEntity.getTableName(), "id");

        assertTrue(joinTable.getColumns().containsKey(ownerFkName));
        assertTrue(joinTable.getColumns().containsKey(targetFkName));

        ColumnModel ownerFk = joinTable.getColumns().get(ownerFkName);
        ColumnModel targetFk = joinTable.getColumns().get(targetFkName);

        assertTrue(ownerFk.isPrimaryKey());
        assertTrue(targetFk.isPrimaryKey());
        assertFalse(ownerFk.isNullable());
        assertFalse(targetFk.isNullable());
        assertEquals(2, joinTable.getRelationships().size(), "Join table should have two FK relationships");
    }

    @Test
    void testOneToOneRelationship_SimplePrimaryKey() {
        // Given
        VariableElement field = mockField("targetRef");
        TypeElement ownerTypeElement = mockOwnerTypeElement(field);
        mockOneToOneAnnotation(field, true); // optional = true
        // No explicit @JoinColumn -> default naming

        ColumnModel targetPk = ColumnModel.builder()
                .columnName("id")
                .javaType("Long")
                .isPrimaryKey(true)
                .build();
        targetEntity.getColumns().put("id", targetPk);
        when(context.findAllPrimaryKeyColumns(targetEntity)).thenReturn(List.of(targetPk));

        TypeElement targetTypeElement = mockTypeElement("com.example.Target");
        mockFieldType(field, targetTypeElement);

        // When
        handler.resolveRelationships(ownerTypeElement, ownerEntity);

        // Then
        String expectedFkName = "targetRef_id";
        assertTrue(ownerEntity.getColumns().containsKey(expectedFkName));
        ColumnModel fkColumn = ownerEntity.getColumns().get(expectedFkName);
        assertEquals("Long", fkColumn.getJavaType());
        assertTrue(fkColumn.isNullable(), "optional=true should allow null");

        assertEquals(1, ownerEntity.getRelationships().size());
        RelationshipModel relationship = ownerEntity.getRelationships().values().iterator().next();
        assertEquals(List.of(expectedFkName), relationship.getColumns());
        assertEquals("target_table", relationship.getReferencedTable());
        assertEquals(List.of("id"), relationship.getReferencedColumns());
    }

    @Test
    void testManyToOne_JoinColumnsSizeMismatch_ShouldFail() {
        // Given
        VariableElement field = mockField("targetField");
        TypeElement ownerTypeElement = mockOwnerTypeElement(field);
        mockManyToOneAnnotation(field, false);

        // Target has composite PK (2 columns)
        ColumnModel targetPk1 = ColumnModel.builder().columnName("id1").javaType("Long").isPrimaryKey(true).build();
        ColumnModel targetPk2 = ColumnModel.builder().columnName("id2").javaType("String").isPrimaryKey(true).build();
        targetEntity.getColumns().put("id1", targetPk1);
        targetEntity.getColumns().put("id2", targetPk2);
        when(context.findAllPrimaryKeyColumns(targetEntity)).thenReturn(List.of(targetPk1, targetPk2));

        // Provide only one @JoinColumn -> size mismatch with composite PK
        mockJoinColumnsAnnotation(field, new String[]{"fk_id1"}, new String[]{"id1"});

        TypeElement targetTypeElement = mockTypeElement("com.example.Target");
        mockFieldType(field, targetTypeElement);

        // When
        handler.resolveRelationships(ownerTypeElement, ownerEntity);

        // Then
        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR),
                contains("@JoinColumns size mismatch"), eq(field));
        assertTrue(ownerEntity.getColumns().isEmpty(), "No FK should be added on mismatch");
        assertTrue(ownerEntity.getRelationships().isEmpty(), "No relationship should be added on mismatch");
    }

    // Helper to mock @OneToOne following existing conventions in this test class
    private void mockOneToOneAnnotation(VariableElement field, boolean optional) {
        OneToOne oneToOne = mock(OneToOne.class);
        lenient().when(oneToOne.optional()).thenReturn(optional);
        lenient().when(oneToOne.cascade()).thenReturn(new jakarta.persistence.CascadeType[0]);
        lenient().when(oneToOne.fetch()).thenReturn(FetchType.EAGER);
        when(field.getAnnotation(OneToOne.class)).thenReturn(oneToOne);

        // Ensure other relationship annotations are null to avoid ambiguity
        when(field.getAnnotation(ManyToOne.class)).thenReturn(null);
        when(field.getAnnotation(OneToMany.class)).thenReturn(null);
        when(field.getAnnotation(ManyToMany.class)).thenReturn(null);

        // Default join annotations to null unless explicitly mocked
        when(field.getAnnotation(JoinColumn.class)).thenReturn(null);
        when(field.getAnnotation(JoinColumns.class)).thenReturn(null);
        when(field.getAnnotation(JoinTable.class)).thenReturn(null);
    }