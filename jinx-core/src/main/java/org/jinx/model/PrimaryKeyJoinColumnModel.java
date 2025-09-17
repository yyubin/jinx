package org.jinx.model;

import lombok.*;

@Builder
@Data
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@AllArgsConstructor
public class PrimaryKeyJoinColumnModel {
    private String name;
    private String referencedColumnName;
    private String columnDefinition;
    private String options;
    private ConstraintModel foreignKeyConstraint;
}
