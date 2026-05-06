package org.jinx.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * JPA {@code @SequenceGenerator} 메타데이터를 담는 모델.
 *
 * <p>숫자 필드는 null이 "설정되지 않음(use database default)"을 의미하며,
 * non-null은 "사용자가 명시적으로 지정한 값"을 의미한다.
 * 이를 통해 {@code 0}이나 음수(내림차순 시퀀스의 INCREMENT BY -1,
 * negative MINVALUE 등)가 silent하게 무시되는 문제를 방지한다.
 */
@Data
@Builder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class SequenceModel {
    private String name;

    @Builder.Default
    private String schema = null;

    @Builder.Default
    private String catalog = null;

    @Builder.Default private Long    initialValue   = null;
    @Builder.Default private Integer allocationSize = null;
    @Builder.Default private Integer cache          = null;
    @Builder.Default private Long    minValue       = null;
    @Builder.Default private Long    maxValue       = null;

    @JsonCreator
    public SequenceModel(
            @JsonProperty("name")           String  name,
            @JsonProperty("schema")         String  schema,
            @JsonProperty("catalog")        String  catalog,
            @JsonProperty("initialValue")   Long    initialValue,
            @JsonProperty("allocationSize") Integer allocationSize,
            @JsonProperty("cache")          Integer cache,
            @JsonProperty("minValue")       Long    minValue,
            @JsonProperty("maxValue")       Long    maxValue) {
        this.name           = name;
        this.schema         = schema;
        this.catalog        = catalog;
        this.initialValue   = initialValue;
        this.allocationSize = allocationSize;
        this.cache          = cache;
        this.minValue       = minValue;
        this.maxValue       = maxValue;
    }
}
