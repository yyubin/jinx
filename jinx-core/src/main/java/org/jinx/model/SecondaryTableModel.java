package org.jinx.model;

import lombok.*;

import java.util.List;
import java.util.Optional;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class SecondaryTableModel {
    private String name;
    private String catalog;
    private String schema;
    private String comment;
    private String options;
    private List<PrimaryKeyJoinColumnModel> pkJoinColumns;
}
