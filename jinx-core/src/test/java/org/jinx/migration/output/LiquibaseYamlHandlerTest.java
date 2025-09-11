package org.jinx.migration.output;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.jinx.migration.liquibase.LiquibaseYamlGenerator;
import org.jinx.migration.liquibase.model.DatabaseChangeLog;
import org.jinx.model.DialectBundle;
import org.jinx.model.DiffResult;
import org.jinx.model.SchemaModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LiquibaseYamlHandlerTest {

    @Test
    @DisplayName("LiquibaseYamlHandler는 LiquibaseYamlGenerator 결과를 YAML로 파일에 기록한다")
    void handle_writesYamlFile(@TempDir Path tempDir) throws IOException {
        // given
        DiffResult diff = mock(DiffResult.class);
        SchemaModel oldSchema = SchemaModel.builder().version("1.0").build();
        SchemaModel newSchema = SchemaModel.builder().version("2.0").build();
        DialectBundle dialect = mock(DialectBundle.class);

        DatabaseChangeLog fakeChangeLog = new DatabaseChangeLog(); // 직렬화할 객체

        try (MockedConstruction<LiquibaseYamlGenerator> mc =
                     mockConstruction(LiquibaseYamlGenerator.class,
                             (mock, ctx) -> when(mock.generate(eq(diff), eq(oldSchema), eq(newSchema), eq(dialect)))
                                     .thenReturn(fakeChangeLog))) {

            LiquibaseYamlHandler handler = new LiquibaseYamlHandler();

            // when
            handler.handle(diff, oldSchema, newSchema, dialect, tempDir);

            // then
            // 파일이 하나 생성되었는지 확인
            try (var files = Files.list(tempDir)) {
                Path yamlFile = files.findFirst().orElseThrow();
                assertTrue(yamlFile.getFileName().toString().startsWith("changelog-"));
                assertTrue(yamlFile.getFileName().toString().endsWith(".yaml"));

                // 내용이 YAML 포맷으로 직렬화되었는지 확인 (Jackson으로 역직렬화)
                ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
                DatabaseChangeLog readBack = mapper.readValue(yamlFile.toFile(), DatabaseChangeLog.class);
                assertNotNull(readBack);
            }
        }
    }
}
