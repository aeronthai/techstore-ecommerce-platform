package com.techstore.order.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.techstore.order.entity.Order;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {
    List<Order> findByCustomerIdOrderByCreatedAtDesc(Long customerId);

    List<Order> findByCustomerIdAndStatusOrderByCreatedAtDesc(Long customerId, String status);

    List<Order> findByStatusOrderByCreatedAtDesc(String status);

    @Query(
            """
			SELECT o.customerId,
				COUNT(o)                        AS orderCount,
				COALESCE(SUM(o.totalPrice), 0)  AS totalSpent
			FROM Order o
			WHERE o.createdAt BETWEEN :from AND :to
			AND o.status NOT IN ('CANCELLED', 'RETURNED', 'PARTIALLY_RETURNED', 'CREATED')
			GROUP BY o.customerId
			ORDER BY (0.4 * COUNT(o) + 0.6 * COALESCE(SUM(o.totalPrice), 0)) DESC
			""")
    List<Object[]> topLoyalCustomers(
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to, Pageable pageable);

    // Danh sách status loại trừ — dùng chung cho cả 3 endpoint
    // CANCELLED, RETURNED, PARTIALLY_RETURNED, CREATED

    // ── Revenue by day ────────────────────────────────────────────────────────────
    @Query(
            """
	SELECT FUNCTION('DATE_FORMAT', o.createdAt, '%Y-%m-%d') AS label,
			COALESCE(SUM(o.totalPrice), 0)                   AS revenue,
			COUNT(o)                                          AS orderCount
	FROM Order o
	WHERE o.createdAt BETWEEN :from AND :to
	AND o.status NOT IN ('CANCELLED', 'RETURNED', 'PARTIALLY_RETURNED', 'CREATED')
	GROUP BY FUNCTION('DATE_FORMAT', o.createdAt, '%Y-%m-%d')
	ORDER BY label ASC
	""")
    List<Object[]> revenueByDay(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    // ── Revenue by month ──────────────────────────────────────────────────────────
    @Query(
            """
	SELECT FUNCTION('DATE_FORMAT', o.createdAt, '%Y-%m') AS label,
			COALESCE(SUM(o.totalPrice), 0)                AS revenue,
			COUNT(o)                                      AS orderCount
	FROM Order o
	WHERE o.createdAt BETWEEN :from AND :to
	AND o.status NOT IN ('CANCELLED', 'RETURNED', 'PARTIALLY_RETURNED', 'CREATED')
	GROUP BY FUNCTION('DATE_FORMAT', o.createdAt, '%Y-%m')
	ORDER BY label ASC
	""")
    List<Object[]> revenueByMonth(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    // ── Revenue by quarter ────────────────────────────────────────────────────────
    @Query(
            """
	SELECT CONCAT(YEAR(o.createdAt), '-Q', QUARTER(o.createdAt)) AS label,
			COALESCE(SUM(o.totalPrice), 0)                         AS revenue,
			COUNT(o)                                               AS orderCount
	FROM Order o
	WHERE o.createdAt BETWEEN :from AND :to
	AND o.status NOT IN ('CANCELLED', 'RETURNED', 'PARTIALLY_RETURNED', 'CREATED')
	GROUP BY YEAR(o.createdAt), QUARTER(o.createdAt)
	ORDER BY YEAR(o.createdAt), QUARTER(o.createdAt)
	""")
    List<Object[]> revenueByQuarter(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    // ── Revenue by year ───────────────────────────────────────────────────────────
    @Query(
            """
	SELECT CAST(YEAR(o.createdAt) AS string) AS label,
			COALESCE(SUM(o.totalPrice), 0)    AS revenue,
			COUNT(o)                          AS orderCount
	FROM Order o
	WHERE o.createdAt BETWEEN :from AND :to
	AND o.status NOT IN ('CANCELLED', 'RETURNED', 'PARTIALLY_RETURNED', 'CREATED')
	GROUP BY YEAR(o.createdAt)
	ORDER BY YEAR(o.createdAt)
	""")
    List<Object[]> revenueByYear(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    // ── Top variants ──────────────────────────────────────────────────────────────
    // Chỉ lấy order_detail ACTIVE (không tính hàng RETURNED)
    // và order phải thuộc status hợp lệ
    @Query(
            """
	SELECT od.variantId,
			od.name,
			SUM(od.quantity)            AS totalQty,
			SUM(od.price * od.quantity) AS totalRevenue
	FROM OrderDetail od
	JOIN od.order o
	WHERE (:from IS NULL OR o.createdAt >= :from)
	AND (:to   IS NULL OR o.createdAt <= :to)
	AND o.status NOT IN ('CANCELLED', 'RETURNED', 'PARTIALLY_RETURNED', 'CREATED')
	AND od.status = 'ACTIVE'
	GROUP BY od.variantId, od.name
	ORDER BY totalQty DESC
	""")
    List<Object[]> topVariants(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to, Pageable pageable);

    // ── Order summary ─────────────────────────────────────────────────────────────
    // Breakdown hiển thị TẤT CẢ status (để FE biết bao nhiêu đơn hủy, đơn mới...)
    // Nhưng totalRevenue ở service layer chỉ cộng các status hợp lệ
    @Query(
            """
	SELECT o.status,
			COUNT(o)                       AS cnt,
			COALESCE(SUM(o.totalPrice), 0) AS revenue
	FROM Order o
	WHERE (:from IS NULL OR o.createdAt >= :from)
	AND (:to   IS NULL OR o.createdAt <= :to)
	AND (:status IS NULL OR o.status = :status)
	GROUP BY o.status
	ORDER BY cnt DESC
	""")
    List<Object[]> orderSummaryByStatus(
            @Param("status") String status, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    // Query theo giờ (TODAY)
    @Query(
            """
SELECT od.variantId,
		od.name,
		FUNCTION('DATE_FORMAT', o.createdAt, '%H:00') AS label,
		SUM(od.quantity)                               AS qty
FROM OrderDetail od
JOIN od.order o
WHERE o.createdAt BETWEEN :from AND :to
	AND od.variantId IN :variantIds
	AND o.status NOT IN ('CANCELLED', 'RETURNED', 'PARTIALLY_RETURNED', 'CREATED')
	AND od.status = 'ACTIVE'
GROUP BY od.variantId, od.name,
		FUNCTION('DATE_FORMAT', o.createdAt, '%H:00')
ORDER BY od.variantId, label
""")
    List<Object[]> productSalesByHour(
            @Param("variantIds") List<Long> variantIds,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    // Query theo ngày (MONTHLY / CUSTOM ngắn)
    @Query(
            """
SELECT od.variantId,
		od.name,
		FUNCTION('DATE_FORMAT', o.createdAt, '%Y-%m-%d') AS label,
		SUM(od.quantity)                                   AS qty
FROM OrderDetail od
JOIN od.order o
WHERE o.createdAt BETWEEN :from AND :to
	AND od.variantId IN :variantIds
	AND o.status NOT IN ('CANCELLED', 'RETURNED', 'PARTIALLY_RETURNED', 'CREATED')
	AND od.status = 'ACTIVE'
GROUP BY od.variantId, od.name,
		FUNCTION('DATE_FORMAT', o.createdAt, '%Y-%m-%d')
ORDER BY od.variantId, label
""")
    List<Object[]> productSalesByDay(
            @Param("variantIds") List<Long> variantIds,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    // Query theo tháng (QUARTERLY / YEARLY / CUSTOM dài)
    @Query(
            """
SELECT od.variantId,
		od.name,
		FUNCTION('DATE_FORMAT', o.createdAt, '%Y-%m') AS label,
		SUM(od.quantity)                               AS qty
FROM OrderDetail od
JOIN od.order o
WHERE o.createdAt BETWEEN :from AND :to
	AND od.variantId IN :variantIds
	AND o.status NOT IN ('CANCELLED', 'RETURNED', 'PARTIALLY_RETURNED', 'CREATED')
	AND od.status = 'ACTIVE'
GROUP BY od.variantId, od.name,
		FUNCTION('DATE_FORMAT', o.createdAt, '%Y-%m')
ORDER BY od.variantId, label
""")
    List<Object[]> productSalesByMonth(
            @Param("variantIds") List<Long> variantIds,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    List<Order> findByStatusInAndCreatedAtBefore(List<String> statuses, LocalDateTime before);
}
