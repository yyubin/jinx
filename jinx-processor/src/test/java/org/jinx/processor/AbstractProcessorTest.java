package org.jinx.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import org.jinx.model.SchemaModel;

import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static com.google.testing.compile.Compiler.javac;
import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;

public abstract class AbstractProcessorTest {

    protected static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 지정된 소스 파일들로 어노테이션 프로세서를 실행하고 컴파일 결과를 반환합니다.
     * @param sources 컴파일할 소스 파일(들)
     * @return Compilation 객체
     */
    protected Compilation compile(JavaFileObject... sources) {
        return javac()
                .withProcessors(new JpaSqlGeneratorProcessor())
                .compile(sources);
    }

    /**
     * 컴파일이 성공적으로 완료되었는지, 그리고 특정 파일이 생성되었는지 검증합니다.
     * @param compilation 컴파일 결과 객체
     * @return 생성된 SchemaModel을 담은 Optional 객체
     */
    protected Optional<SchemaModel> assertCompilationSuccessAndGetSchema(Compilation compilation) {
        assertThat(compilation).succeeded();

        // 생성된 JSON 파일 찾기
        Optional<JavaFileObject> generatedJsonFile = compilation.generatedFiles().stream()
                .filter(file ->
                        file.getName().startsWith("/CLASS_OUTPUT/jinx/schema-") && file.getName().endsWith(".json")
                )
                .findFirst();

        if (generatedJsonFile.isEmpty()) {
            throw new AssertionError("Schema JSON file was not generated in the expected location.");
        }

        try (InputStream inputStream = generatedJsonFile.get().openInputStream()) {
            String jsonContent = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            System.out.println("Generated Schema JSON:\n" + jsonContent); // 디버깅을 위한 출력
            SchemaModel schemaModel = objectMapper.readValue(jsonContent, SchemaModel.class);
            return Optional.of(schemaModel);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read or parse generated schema JSON", e);
        }
    }

    /**
     * 컴파일이 특정 에러 메시지와 함께 실패했는지 검증합니다.
     * @param expectedErrorMsg 기대하는 에러 메시지
     * @param sources 컴파일할 소스 파일(들)
     */
    protected void assertCompilationError(String expectedErrorMsg, JavaFileObject... sources) {
        Compilation compilation = compile(sources);
        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining(expectedErrorMsg);
    }

    /**
     * 테스트용 소스 파일을 로드합니다.
     * @param path `test/resources` 내의 파일 경로
     * @return JavaFileObject
     */
    protected JavaFileObject source(String path) {
        return JavaFileObjects.forResource(path);
    }
}