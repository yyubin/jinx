package org.jinx.model;

import lombok.*;

@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PrimaryKeyJoinColumnModel {
    private String name;
    private String referencedColumnName;
    private String columnDefinition;
    private String options;
    private ConstraintModel foreignKeyConstraint;
}
