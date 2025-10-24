package org.jinx.processor;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import org.jinx.model.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

class DeferredToOneRelationshipProcessingTest extends AbstractProcessorTest {

    @Test
    @DisplayName("ManyToOne FK는 참조된 엔티티가 나중에 처리되더라도 반드시 생성되어야 합니다.")
    void manyToOne_deferred_processing_creates_fk_after_retry() {
        // Simulates: ImageEntity processed before PersonaEntity (alphabetically)
        // ImageEntity has @ManyToOne PersonaEntity

        JavaFileObject source = JavaFileObjects.forSourceString("test.TestEntities", """
            package test;

            import jakarta.persistence.*;

            @Entity
            @Table(name = "image")
            class ImageEntity {
                @Id
                @GeneratedValue(strategy = GenerationType.IDENTITY)
                private Long id;

                @Column(nullable = false)
                private String url;

                // Reference to PersonaEntity (processed later alphabetically)
                @ManyToOne(fetch = FetchType.LAZY)
                @JoinColumn(name = "persona_id", nullable = false)
                private PersonaEntity persona;
            }

            @Entity
            @Table(name = "persona")
            class PersonaEntity {
                @Id
                @GeneratedValue(strategy = GenerationType.IDENTITY)
                private Long id;

                @Column(nullable = false, length = 100)
                private String name;

                @OneToMany(mappedBy = "persona", cascade = CascadeType.ALL, orphanRemoval = true)
                private java.util.List<ImageEntity> images = new java.util.ArrayList<>();
            }
            """);

        Compilation compilation = compile(source);
        Optional<SchemaModel> schemaOpt = assertCompilationSuccessAndGetSchema(compilation);
        assertThat(schemaOpt).isPresent();
        SchemaModel result = schemaOpt.get();

        EntityModel imageEntity = result.getEntities().get("test.ImageEntity");
        EntityModel personaEntity = result.getEntities().get("test.PersonaEntity");

        assertThat(imageEntity).isNotNull();
        assertThat(personaEntity).isNotNull();

        // Verify: ImageEntity has FK column persona_id
        ColumnModel fkColumn = imageEntity.findColumn("image", "persona_id");
        assertThat(fkColumn).as("FK column persona_id should exist in image table").isNotNull();
        assertThat(fkColumn.getJavaType()).isEqualTo("java.lang.Long");
        assertThat(fkColumn.isNullable()).isFalse(); // nullable = false in @JoinColumn

        // Verify: ImageEntity has MANY_TO_ONE relationship
        assertThat(imageEntity.getRelationships()).hasSize(1);
        RelationshipModel relationship = imageEntity.getRelationships().values().iterator().next();
        assertThat(relationship.getType()).isEqualTo(RelationshipType.MANY_TO_ONE);
        assertThat(relationship.getTableName()).isEqualTo("image");
        assertThat(relationship.getColumns()).containsExactly("persona_id");
        assertThat(relationship.getReferencedTable()).isEqualTo("persona");
        assertThat(relationship.getReferencedColumns()).containsExactly("id");

        // Verify: FK index was created
        assertThat(imageEntity.getIndexes()).hasSize(1);
        assertThat(imageEntity.getIndexes().values().iterator().next().getColumnNames())
                .containsExactly("persona_id");
    }

    @Test
    @DisplayName("여러 개의 ManyToOne 관계는 모두 지연 처리(deferred processing)를 통해 생성되어야 합니다.")
    void multiple_manyToOne_deferred_processing() {
        JavaFileObject source = JavaFileObjects.forSourceString("test.TestEntities", """
            package test;

            import jakarta.persistence.*;

            @Entity
            @Table(name = "image")
            class ImageEntity {
                @Id
                @GeneratedValue(strategy = GenerationType.IDENTITY)
                private Long id;

                @Column(nullable = false)
                private String url;

                @ManyToOne(fetch = FetchType.LAZY)
                @JoinColumn(name = "persona_id", nullable = false)
                private PersonaEntity persona;
            }

            @Entity
            @Table(name = "passive")
            class PassiveEntity {
                @Id
                @GeneratedValue(strategy = GenerationType.IDENTITY)
                private Long id;

                @Column(nullable = false, length = 100)
                private String name;

                @ManyToOne(fetch = FetchType.LAZY)
                @JoinColumn(name = "persona_id", nullable = false)
                private PersonaEntity persona;
            }

            @Entity
            @Table(name = "persona")
            class PersonaEntity {
                @Id
                @GeneratedValue(strategy = GenerationType.IDENTITY)
                private Long id;

                @Column(nullable = false, length = 100)
                private String name;

                @ManyToOne(fetch = FetchType.LAZY)
                @JoinColumn(name = "sinner_id", nullable = false)
                private SinnerEntity sinner;

                @OneToMany(mappedBy = "persona", cascade = CascadeType.ALL)
                private java.util.List<PassiveEntity> passives = new java.util.ArrayList<>();

                @OneToMany(mappedBy = "persona", cascade = CascadeType.ALL)
                private java.util.List<ImageEntity> images = new java.util.ArrayList<>();
            }

            @Entity
            @Table(name = "sinner")
            class SinnerEntity {
                @Id
                @GeneratedValue(strategy = GenerationType.IDENTITY)
                private Long id;

                @Column(nullable = false, length = 50)
                private String name;

                @OneToMany(mappedBy = "sinner", cascade = CascadeType.ALL)
                private java.util.List<PersonaEntity> personas = new java.util.ArrayList<>();
            }
            """);

        Compilation compilation = compile(source);
        Optional<SchemaModel> schemaOpt = assertCompilationSuccessAndGetSchema(compilation);
        assertThat(schemaOpt).isPresent();
        SchemaModel result = schemaOpt.get();

        EntityModel passiveEntity = result.getEntities().get("test.PassiveEntity");
        EntityModel imageEntity = result.getEntities().get("test.ImageEntity");
        EntityModel personaEntity = result.getEntities().get("test.PersonaEntity");
        EntityModel sinnerEntity = result.getEntities().get("test.SinnerEntity");

        assertThat(passiveEntity).isNotNull();
        assertThat(imageEntity).isNotNull();
        assertThat(personaEntity).isNotNull();
        assertThat(sinnerEntity).isNotNull();

        // Verify: PassiveEntity -> PersonaEntity FK
        ColumnModel passiveFk = passiveEntity.findColumn("passive", "persona_id");
        assertThat(passiveFk).as("passive.persona_id FK should exist").isNotNull();
        assertThat(passiveEntity.getRelationships()).hasSize(1);

        // Verify: ImageEntity -> PersonaEntity FK
        ColumnModel imageFk = imageEntity.findColumn("image", "persona_id");
        assertThat(imageFk).as("image.persona_id FK should exist").isNotNull();
        assertThat(imageEntity.getRelationships()).hasSize(1);

        // Verify: PersonaEntity -> SinnerEntity FK
        ColumnModel personaFk = personaEntity.findColumn("persona", "sinner_id");
        assertThat(personaFk).as("persona.sinner_id FK should exist").isNotNull();
        assertThat(personaEntity.getRelationships()).hasSize(1);

        // Verify: All relationships are MANY_TO_ONE
        assertThat(passiveEntity.getRelationships().values())
                .allMatch(rel -> rel.getType() == RelationshipType.MANY_TO_ONE);
        assertThat(imageEntity.getRelationships().values())
                .allMatch(rel -> rel.getType() == RelationshipType.MANY_TO_ONE);
        assertThat(personaEntity.getRelationships().values())
                .allMatch(rel -> rel.getType() == RelationshipType.MANY_TO_ONE);
    }

    @Test
    @DisplayName("OneToOne 관계는 지연 처리(deferred processing)를 통해 UNIQUE 제약 조건과 함께 생성되어야 합니다.")
    void oneToOne_deferred_processing_creates_fk_with_unique() {
        JavaFileObject source = JavaFileObjects.forSourceString("test.TestEntities", """
            package test;

            import jakarta.persistence.*;

            @Entity
            @Table(name = "effect")
            class EffectEntity {
                @Id
                @GeneratedValue(strategy = GenerationType.IDENTITY)
                private Long id;

                @Column(nullable = false)
                private String description;

                @OneToOne(fetch = FetchType.LAZY)
                @JoinColumn(name = "passive_id", nullable = false)
                private PassiveEntity passive;
            }

            @Entity
            @Table(name = "passive")
            class PassiveEntity {
                @Id
                @GeneratedValue(strategy = GenerationType.IDENTITY)
                private Long id;

                @Column(nullable = false)
                private String name;

                @OneToOne(mappedBy = "passive", cascade = CascadeType.ALL, orphanRemoval = true)
                private EffectEntity effect;
            }
            """);

        Compilation compilation = compile(source);
        Optional<SchemaModel> schemaOpt = assertCompilationSuccessAndGetSchema(compilation);
        assertThat(schemaOpt).isPresent();
        SchemaModel result = schemaOpt.get();

        EntityModel effectEntity = result.getEntities().get("test.EffectEntity");
        EntityModel passiveEntity = result.getEntities().get("test.PassiveEntity");

        assertThat(effectEntity).isNotNull();
        assertThat(passiveEntity).isNotNull();

        // Verify: EffectEntity has FK column passive_id
        ColumnModel fkColumn = effectEntity.findColumn("effect", "passive_id");
        assertThat(fkColumn).as("FK column passive_id should exist").isNotNull();
        assertThat(fkColumn.isNullable()).isFalse();

        // Verify: ONE_TO_ONE relationship
        assertThat(effectEntity.getRelationships()).hasSize(1);
        RelationshipModel relationship = effectEntity.getRelationships().values().iterator().next();
        assertThat(relationship.getType()).isEqualTo(RelationshipType.ONE_TO_ONE);
        assertThat(relationship.getReferencedTable()).isEqualTo("passive");

        assertThat(effectEntity.getConstraints().size()).isEqualTo(1);

    }
}
