package org.jinx.migration.differs;

import org.jinx.migration.differs.model.TableKey;
import org.jinx.model.ColumnModel;
import org.jinx.model.DiffResult;
import org.jinx.model.EntityModel;
import org.jinx.model.SchemaModel;
import org.jinx.model.naming.CaseNormalizer;

import java.util.*;
import java.util.stream.Collectors;

public class TableDiffer implements Differ {
    private final CaseNormalizer normalizer;

    public TableDiffer() {
        this(CaseNormalizer.lower());
    }
    public TableDiffer(CaseNormalizer normalizer) {
        this.normalizer = Objects.requireNonNull(normalizer, "normalizer must not be null");
    }


    @Override
    public void diff(SchemaModel oldSchema, SchemaModel newSchema, DiffResult result) {
        var oldEntities = Optional.ofNullable(oldSchema.getEntities()).orElseGet(Map::of);
        var newEntities = Optional.ofNullable(newSchema.getEntities()).orElseGet(Map::of);

        Set<String> oldNames = new LinkedHashSet<>(oldEntities.keySet());
        Set<String> newNames = new LinkedHashSet<>(newEntities.keySet());

        // old: 이름→키
        Map<String, TableKey> oldNameToKey = new LinkedHashMap<>();
        for (var e : oldEntities.values()) {
            oldNameToKey.put(e.getEntityName(), TableKey.of(e, normalizer));
        }
        // new: 키→이름들
        Map<TableKey, List<String>> newKeyToNames = new LinkedHashMap<>();
        for (var e : newEntities.values()) {
            TableKey k = TableKey.of(e, normalizer);
            newKeyToNames.computeIfAbsent(k, _k -> new ArrayList<>()).add(e.getEntityName());
        }

        // 이름이 바뀐 후보만 추림
        Set<String> oldOnly = oldNames.stream().filter(n -> !newNames.contains(n))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> newOnly = newNames.stream().filter(n -> !oldNames.contains(n))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        for (String oldName : new ArrayList<>(oldOnly)) {
            TableKey key = oldNameToKey.get(oldName);
            List<String> candidates = newKeyToNames.getOrDefault(key, List.of()).stream()
                    .filter(newOnly::contains)
                    .toList();

            if (candidates.size() == 1) {
                String newName = candidates.get(0);
                var oldEntity = oldEntities.get(oldName);
                var newEntity = newEntities.get(newName);
                result.getRenamedTables().add(DiffResult.RenamedTable.builder()
                        .oldEntity(oldEntity)
                        .newEntity(newEntity)
                        .changeDetail("Table renamed from " + oldEntity.getTableName() + " to " + newEntity.getTableName())
                        .build());
                oldOnly.remove(oldName);
                newOnly.remove(newName);
            } else if (candidates.size() > 1) {
                result.getWarnings().add("[AMBIGUOUS-RENAME] old='" + oldName + "' candidates=" + candidates
                        + " pk=" + key.pkColKeys());
            }
        }

        newOnly.forEach(name -> result.getAddedTables().add(newEntities.get(name)));
        oldOnly.forEach(name -> result.getDroppedTables().add(oldEntities.get(name)));
    }

}