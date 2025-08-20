package org.jinx.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Optional;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SecondaryTableModel {
    private String name;
    private Optional<String> catalog;
    private Optional<String> schema;
    private Optional<String> comment;
    private Optional<String> options;
    private List<PrimaryKeyJoinColumnModel> pkJoinColumns;
}
