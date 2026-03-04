package entities.getter;

import jakarta.persistence.*;

/**
 * Bug 1 검증용 엔티티.
 *
 * isSuccessful(), isRefundable(), getDisplayName() 은 백킹 필드가 없는
 * 순수 계산 메서드이므로 DDL 컬럼이 생성되어서는 안 된다.
 */
@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    // ── 실제 필드를 위한 getter ───────────────────────
    public Long getId() { return id; }
    public PaymentStatus getStatus() { return status; }

    // ── 백킹 필드 없는 순수 계산 메서드 (컬럼 생성 금지) ──
    public boolean isSuccessful() {
        return status == PaymentStatus.COMPLETED;
    }

    public boolean isRefundable() {
        return status == PaymentStatus.COMPLETED
            || status == PaymentStatus.PARTIALLY_REFUNDED;
    }

    public String getDisplayName() {
        return "Payment#" + id;
    }
}
