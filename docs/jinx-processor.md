# jinx-processor 모듈 분석

## 개요

jinx-processor는 JPA 어노테이션을 분석하여 스키마 모델을 JSON 형태로 생성하는 APT(Annotation Processing Tool) 기반 프로세서입니다. 컴파일 타임에 JPA 엔티티 클래스들을 분석하고 데이터베이스 스키마 정보를 추출합니다.

## 핵심 아키텍처

### 1. 메인 프로세서
- **JpaSqlGeneratorProcessor** (`org.jinx.processor.JpaSqlGeneratorProcessor`)
  - `@AutoService(Processor.class)`로 등록된 APT 프로세서
  - JPA 어노테이션들(`jakarta.persistence.*`)을 지원
  - 컴파일 타임에 어노테이션이 붙은 클래스들을 분석

### 2. 처리 컨텍스트
- **ProcessingContext** (`org.jinx.context.ProcessingContext`)
  - 프로세싱 환경과 스키마 모델을 관리
  - 엔티티 간 참조 해결을 위한 캐싱 및 지연 처리 큐 관리
  - 네이밍 전략 및 진단 메시지 처리

### 3. 어노테이션 분석 시스템
- **AttributeDescriptorFactory** (`org.jinx.descriptor.AttributeDescriptorFactory`)
  - JPA Access 전략(FIELD/PROPERTY)에 따른 속성 디스크립터 생성
  - 필드와 Getter 메서드의 어노테이션을 통합 분석
  - 상속 계층에서 속성 수집

- **AttributeDescriptor**
  - 필드 또는 프로퍼티에 대한 통합된 메타데이터 인터페이스
  - `FieldAttributeDescriptor`, `PropertyAttributeDescriptor` 구현체

## 주요 처리 흐름

### 1. 초기화 단계 (`init` 메서드)
```java
@Override
public synchronized void init(ProcessingEnvironment processingEnv) {
    // SchemaModel 생성 (버전은 현재 시간)
    SchemaModel schemaModel = SchemaModel.builder()
        .version(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")))
        .build();
    
    // ProcessingContext 초기화
    this.context = new ProcessingContext(processingEnv, schemaModel);
    
    // 각종 핸들러 초기화
    // - SequenceHandler, ColumnHandler, RelationshipHandler 등
}
```

### 2. 어노테이션 처리 단계 (`process` 메서드)

#### 2.1 첫 번째 라운드 처리
1. **@Converter(autoApply=true) 처리**
   - 자동 적용되는 AttributeConverter 수집

2. **@MappedSuperclass, @Embeddable 처리**
   - 상속 및 임베딩을 위한 기반 클래스 등록

3. **@Entity 처리**
   - `EntityHandler.handle(TypeElement)`로 각 엔티티 분석

#### 2.2 최종 라운드 처리 (`roundEnv.processingOver()`)
1. **상속 관계 해석**
   - `InheritanceHandler.resolveInheritance()` 호출

2. **PK 검증**
   - 모든 엔티티의 Primary Key 존재 여부 확인

3. **지연된 FK 처리**
   - JOINED 상속 관련 Foreign Key 처리
   - 최대 5회 시도로 순환 참조 해결

4. **JSON 출력**
   - `context.saveModelToJson()` 호출

### 3. 엔티티 처리 과정 (`EntityHandler`)

#### 3.1 엔티티 기본 정보 설정
```java
EntityModel entity = createEntityModel(typeElement);
```
- `@Table` 어노테이션에서 테이블명 추출
- 중복 엔티티 검증

#### 3.2 메타데이터 처리
- **테이블 메타데이터**: `@Table(schema, catalog, comment)` 처리
- **제너레이터**: `@SequenceGenerator`, `@TableGenerator` 처리
- **제약조건**: `@Constraint`, `@Constraints` 처리

#### 3.3 복합키 처리
- `@EmbeddedId` 지원 (`@IdClass`는 미지원)
- `EmbeddedHandler.processEmbeddedId()` 호출

#### 3.4 필드 처리 (AttributeDescriptor 기반)
```java
public void processFieldsWithAttributeDescriptor(TypeElement typeElement, EntityModel entity) {
    List<AttributeDescriptor> descriptors = context.getCachedDescriptors(typeElement);
    
    for (AttributeDescriptor descriptor : descriptors) {
        processAttributeDescriptor(descriptor, entity, tableMappings);
    }
}
```

각 속성 유형별 처리:
- **@ElementCollection**: `ElementCollectionHandler`
- **@Embedded**: `EmbeddedHandler`
- **관계 어노테이션** (`@OneToOne`, `@OneToMany`, `@ManyToOne`, `@ManyToMany`): `RelationshipHandler`
- **일반 컬럼**: `ColumnHandler`

### 4. 컬럼 처리 시스템

#### 4.1 ColumnHandler
- **AttributeBasedEntityResolver**: AttributeDescriptor 기반 컬럼 생성
- **EntityFieldResolver**: 일반 필드 기반 컬럼 생성
- **CollectionElementResolver**: 컬렉션 요소 컬럼 생성

#### 4.2 컬럼 생성 과정
1. JPA 어노테이션 분석 (`@Column`, `@Id`, `@GeneratedValue` 등)
2. Java 타입을 JDBC 타입으로 매핑
3. 제약조건 생성 (`@Column(unique=true)` → UNIQUE 제약)
4. 테이블명 검증 (Primary/Secondary 테이블)

## 스키마 모델 구조

### 1. SchemaModel
```java
public class SchemaModel {
    private String version;                                    // 생성 시간 기반 버전
    private Map<String, EntityModel> entities;                // 엔티티 맵
    private Map<String, SequenceModel> sequences;             // 시퀀스 맵
    private Map<String, TableGeneratorModel> tableGenerators; // 테이블 제너레이터 맵
    private Map<String, ClassInfoModel> mappedSuperclasses;   // MappedSuperclass 맵
    private Map<String, ClassInfoModel> embeddables;          // Embeddable 맵
}
```

### 2. EntityModel
```java
public class EntityModel {
    private String entityName;                                 // 엔티티명 (FQCN)
    private String tableName;                                  // 테이블명
    private Map<String, ColumnModel> columns;                 // 컬럼 맵 (테이블명::컬럼명 키)
    private Map<String, IndexModel> indexes;                  // 인덱스 맵
    private Map<String, ConstraintModel> constraints;         // 제약조건 맵
    private Map<String, RelationshipModel> relationships;     // 관계 맵
    private List<SecondaryTableModel> secondaryTables;        // 보조 테이블 목록
}
```

### 3. ColumnModel
```java
public class ColumnModel {
    private String tableName;                                  // 소속 테이블명
    private String columnName;                                 // 컬럼명
    private String javaType;                                   // Java 타입
    private boolean isPrimaryKey;                              // PK 여부
    private boolean isNullable;                                // NULL 허용 여부
    private int length, precision, scale;                      // 길이/정밀도/스케일
    private GenerationStrategy generationStrategy;            // 키 생성 전략
    private String sequenceName;                               // 시퀀스명 (SEQUENCE 전략)
    // ... 기타 메타데이터
}
```

## 관계 처리

### 1. 관계 해석 과정
- **RelationshipHandler**: 관계 어노테이션 분석
- **RelationshipSupport**: 타겟 엔티티 해석 및 조인 컬럼 처리
- **MappedBy 순환 참조 방지**: `mappedByVisitedSet`으로 무한 루프 방지

### 2. 조인 테이블 처리
- `@JoinTable`: 다대다 관계의 중간 테이블 생성
- `@JoinColumn`: Foreign Key 컬럼 생성
- `@MapsId`: 복합키에서 관계 컬럼이 PK 역할 겸용

### 3. 상속 처리
- **JOINED 전략**: `@PrimaryKeyJoinColumn`으로 부모-자식 테이블 조인
- **지연 처리**: 부모 엔티티가 먼저 처리되어야 자식 처리 가능

## JSON 출력

### 1. 출력 위치
```java
public void saveModelToJson() {
    String fileName = "jinx/schema-" + schemaModel.getVersion() + ".json";
    FileObject file = processingEnv.getFiler()
        .createResource(StandardLocation.CLASS_OUTPUT, "", fileName);
}
```
- 컴파일 결과물 디렉토리의 `jinx/schema-{버전}.json` 파일로 출력

### 2. 직렬화
- Jackson ObjectMapper 사용
- `SerializationFeature.INDENT_OUTPUT` 활성화로 가독성 있는 JSON 생성

## 지원하는 JPA 어노테이션

### 엔티티 레벨
- `@Entity`, `@Table`, `@SecondaryTable`, `@SecondaryTables`
- `@Inheritance`, `@DiscriminatorColumn`, `@DiscriminatorValue`
- `@PrimaryKeyJoinColumn`, `@PrimaryKeyJoinColumns`

### 필드/프로퍼티 레벨
- `@Id`, `@EmbeddedId`, `@GeneratedValue`
- `@Column`, `@JoinColumn`, `@JoinColumns`, `@JoinTable`
- `@OneToOne`, `@OneToMany`, `@ManyToOne`, `@ManyToMany`
- `@Embedded`, `@ElementCollection`
- `@Enumerated`, `@Temporal`, `@Lob`, `@Version`
- `@Convert`, `@MapsId`

### 제너레이터
- `@SequenceGenerator`, `@TableGenerator`
- `@Converter` (autoApply=true)

## 특별 기능

### 1. Access 전략 지원
- `@Access(AccessType.FIELD)`: 필드 기반 접근
- `@Access(AccessType.PROPERTY)`: 프로퍼티 기반 접근
- 엔티티별 기본 전략 자동 감지

### 2. AttributeDescriptor 캐싱
- 양방향 관계 해석 시 재계산 방지
- `ProcessingContext.getCachedDescriptors()` 메서드

### 3. 지연 처리 시스템
- 의존성이 있는 엔티티들의 처리 순서 자동 조정
- `@MapsId`, JOINED 상속 등의 복잡한 관계 해결

### 4. 오류 처리
- 컴파일 타임 검증 및 오류 메시지 출력
- 잘못된 어노테이션 사용이나 누락된 PK에 대한 명확한 진단

## 설정 옵션

### 컴파일러 옵션
- `jinx.naming.maxLength`: 생성되는 제약조건/인덱스 이름의 최대 길이 (기본값: 30)

### 예시
```bash
javac -Ajinx.naming.maxLength=63 *.java
```

이 문서는 jinx-processor 모듈의 전체적인 동작 원리와 구조를 설명합니다. JPA 어노테이션을 컴파일 타임에 분석하여 구조화된 스키마 정보를 JSON으로 출력하는 것이 핵심 역할입니다.