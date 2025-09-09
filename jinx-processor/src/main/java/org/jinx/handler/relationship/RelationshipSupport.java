package org.jinx.handler.relationship;

import jakarta.persistence.*;
import org.jinx.context.ProcessingContext;
import org.jinx.descriptor.AttributeDescriptor;
import org.jinx.model.*;

import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.util.*;

/**
 * Utility class providing common validation and support functions for relationship processing
 */
public final class RelationshipSupport {
    
    private final ProcessingContext context;
    
    public RelationshipSupport(ProcessingContext context) {
        this.context = context;
    }
    
    /**
     * Resolves the table name for a JoinColumn, with fallback to primary table
     * @return the resolved table name
     */
    public String resolveJoinColumnTable(JoinColumn jc, EntityModel owner) {
        String primary = owner.getTableName();
        if (jc == null || jc.table().isEmpty()) return primary;
        String req = jc.table();
        boolean ok = primary.equals(req) || 
            owner.getSecondaryTables().stream().anyMatch(st -> st.getName().equals(req));
        if (!ok) {
            context.getMessager().printMessage(Diagnostic.Kind.WARNING,
               "JoinColumn.table='" + req + "' is not a primary/secondary table of " + owner.getEntityName()
               + ". Falling back to primary '" + primary + "'.");
            return primary;
        }
        return req;
    }
    

    public Optional<TypeElement> resolveTargetEntity(AttributeDescriptor attr,
                                                      ManyToOne m2o, OneToOne o2o, OneToMany o2m, ManyToMany m2m) {
        // 1) 명시적 targetEntity 우선 (APT 안전)
        TypeElement explicit = null;
        if (m2o != null) explicit = classValToTypeElement(() -> m2o.targetEntity());
        else if (o2o != null) explicit = classValToTypeElement(() -> o2o.targetEntity());
        else if (o2m != null) explicit = classValToTypeElement(() -> o2m.targetEntity());
        else if (m2m != null) explicit = classValToTypeElement(() -> m2m.targetEntity());
        if (explicit != null) return Optional.of(explicit);

        // 2) 컬렉션이면 제네릭 인자 사용
        if ((o2m != null) || (m2m != null)) {
            return attr.genericArg(0).map(dt -> (TypeElement) dt.asElement());
        }
        // 3) 필드/프로퍼티 타입으로 추론
        return Optional.ofNullable(getReferencedTypeElement(attr.type()));
    }

    /**
     * 클래스값(annotation)에서 TypeElement를 안전하게 추출합니다.
     * APT 환경에서 MirroredTypeException을 적절히 처리합니다.
     */
    private TypeElement classValToTypeElement(java.util.function.Supplier<Class<?>> getter) {
        try {
            Class<?> clz = getter.get();
            if (clz == void.class) return null;
            return context.getElementUtils().getTypeElement(clz.getName());
        } catch (javax.lang.model.type.MirroredTypeException mte) {
            return getReferencedTypeElement(mte.getTypeMirror());
        } catch (Throwable t) {
            return null;
        }
    }

    private TypeElement getReferencedTypeElement(TypeMirror typeMirror) {
        if (typeMirror instanceof DeclaredType declaredType && declaredType.asElement() instanceof TypeElement) {
            return (TypeElement) declaredType.asElement();
        }
        return null;
    }

    /**
     * 테이블명을 고려하여 컴럼을 찾는 헬퍼 메서드
     * 주/보조 테이블에 동일한 컴럼명이 있을 때 충돌 방지
     */
    public ColumnModel findColumn(EntityModel entity, String tableName, String columnName) {
        return entity.findColumn(tableName, columnName);
    }

    /**
     * 컴럼을 엔티티 모델에 추가하는 헬퍼 메서드
     * 테이블과 컬럼명을 조합한 키를 사용하여 충돌 방지
     */
    public void putColumn(EntityModel entity, ColumnModel column) {
        entity.putColumn(column);
    }

    public boolean allSameConstraintMode(List<JoinColumn> jcs) {
        if (jcs.isEmpty()) return true;
        ConstraintMode first = jcs.get(0).foreignKey().value();
        for (JoinColumn jc : jcs) {
            if (jc.foreignKey().value() != first) return false;
        }
        return true;
    }

    public List<CascadeType> toCascadeList(jakarta.persistence.CascadeType[] arr) {
        return arr == null ? Collections.emptyList() : Arrays.stream(arr).toList();
    }

    /**
     * FK 컬럼에 자동 인덱스 생성 (성능 향상을 위해)
     * PK/UNIQUE로 이미 커버된 경우는 생략
     */
    public void addForeignKeyIndex(EntityModel entity, List<String> fkColumns, String tableName) {
        if (fkColumns == null || fkColumns.isEmpty()) return;
        if (coveredByPkOrUnique(entity, tableName, fkColumns)) return;

        // 인덱스 컬럼 순서는 "FK 컬럼이 생성된 순서"를 유지하세요 (아래 3번 참고)
        List<String> colsInOrder = List.copyOf(fkColumns);
        if (hasSameIndex(entity, tableName, colsInOrder)) return;
        String indexName = context.getNaming().ixName(tableName, colsInOrder);
        if (entity.getIndexes().containsKey(indexName)) return;

        IndexModel ix = IndexModel.builder()
                .indexName(indexName).tableName(tableName)
                .columnNames(colsInOrder).build();
        entity.getIndexes().put(indexName, ix);
    }

    /**
     * FK 컬럼 집합이 PK나 UNIQUE 제약으로 이미 커버되는지 확인
     */
    public boolean coveredByPkOrUnique(EntityModel e, String table, List<String> cols) {
        Set<String> want = new HashSet<>(cols);
        for (ConstraintModel c : e.getConstraints().values()) {
            if (!Objects.equals(table, c.getTableName())) continue;
            if (c.getType() == ConstraintType.PRIMARY_KEY || c.getType() == ConstraintType.UNIQUE) {
                if (new HashSet<>(c.getColumns()).equals(want)) return true;
            }
        }
        return false;
    }

    public boolean hasSameIndex(EntityModel e, String table, List<String> cols) {
        for (IndexModel im : e.getIndexes().values()) {
            if (Objects.equals(table, im.getTableName()) && im.getColumnNames().equals(cols)) return true;
        }
        return false;
    }

    /**
     * @MapsId 등에서 사용할 PK 승격 헬퍼 메소드
     * 지정된 컬럼들을 PK로 승격하고 관련 제약을 설정
     */
    public void promoteColumnsToPrimaryKey(EntityModel entity, String table, List<String> cols) {
        // 1) 컬럼 존재/타입 보강 및 PK/NULL 고정
        for (String col : cols) {
            ColumnModel c = entity.findColumn(table, col);
            if (c == null) {
                context.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Missing FK column '" + col + "' for @MapsId promotion on table '" + table + "'.");
                entity.setValid(false);
                return;
            }
            c.setPrimaryKey(true);
            c.setNullable(false);
            if (c.getTableName() == null || c.getTableName().isBlank()) {
                c.setTableName(table);
            }
        }

        // 2) PK 제약 구성/병합
        String pkName = context.getNaming().pkName(table, cols);
        entity.getConstraints().put(pkName, ConstraintModel.builder()
                .name(pkName)
                .type(ConstraintType.PRIMARY_KEY)
                .tableName(table)
                .columns(new ArrayList<>(cols))
                .build());

        // 3) UNIQUE 중복 커버는 자연스럽게 PK가 대체(있다면 그대로 두어도 무해)
    }
}
