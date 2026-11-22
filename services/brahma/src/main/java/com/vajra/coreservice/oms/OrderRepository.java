package com.vajra.coreservice.oms;

import com.vajra.coreservice.connector.Venue;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Tables {@code core.orders} and {@code core.order_transition}. The symbol comes from a join on {@code instrument}. */
@Repository
public class OrderRepository {

    private static final String SELECT = "select o.id, o.client_order_id, o.idempotency_key, o.venue, o.instrument_id, "
            + "i.symbol, o.side, o.type, o.size, o.limit_price, o.time_in_force, o.reduce_only, o.state, "
            + "o.filled_size, o.avg_fill_price, o.fees, o.venue_order_id, o.source, o.reason, o.created_at, "
            + "o.updated_at, o.version from orders o join instrument i on i.id = o.instrument_id ";

    private final JdbcClient jdbc;

    public OrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Throws {@link org.springframework.dao.DuplicateKeyException} when {@code client_order_id} already exists. */
    public void insert(Order o, String requestHash, UUID signalId) {
        jdbc.sql("insert into orders (id, client_order_id, idempotency_key, request_hash, venue, instrument_id, side, "
                        + "type, size, limit_price, time_in_force, reduce_only, state, filled_size, avg_fill_price, "
                        + "fees, venue_order_id, source, reason, created_at, updated_at, version, signal_id) values "
                        + "(:id, :clientOrderId, :idempotencyKey, :requestHash, :venue, :instrumentId, :side, :type, "
                        + ":size, :limitPrice, :timeInForce, :reduceOnly, :state, :filledSize, :avgFillPrice, :fees, "
                        + ":venueOrderId, :source, :reason, :createdAt, :updatedAt, :version, :signalId)")
                .param("id", o.id())
                .param("clientOrderId", o.clientOrderId())
                .param("idempotencyKey", o.idempotencyKey())
                .param("requestHash", requestHash)
                .param("venue", o.venue().name())
                .param("instrumentId", o.instrumentId())
                .param("side", o.side().name())
                .param("type", o.type().name())
                .param("size", o.size())
                .param("limitPrice", o.limitPrice())
                .param("timeInForce", o.timeInForce().name())
                .param("reduceOnly", o.reduceOnly())
                .param("state", o.state().name())
                .param("filledSize", o.filledSize())
                .param("avgFillPrice", o.avgFillPrice())
                .param("fees", o.fees())
                .param("venueOrderId", o.venueOrderId())
                .param("source", o.source().name())
                .param("reason", o.reason())
                .param("createdAt", utc(o.createdAt()))
                .param("updatedAt", utc(o.updatedAt()))
                .param("version", o.version())
                .param("signalId", signalId)
                .update();
    }

    public Optional<Order> find(UUID id) {
        return jdbc.sql(SELECT + "where o.id = :id").param("id", id).query(OrderRepository::map).optional();
    }

    public Optional<Order> findByClientOrderId(String clientOrderId) {
        return jdbc.sql(SELECT + "where o.client_order_id = :id")
                .param("id", clientOrderId)
                .query(OrderRepository::map)
                .optional();
    }

    public Optional<String> requestHash(UUID id) {
        return jdbc.sql("select request_hash from orders where id = :id")
                .param("id", id)
                .query(String.class)
                .optional();
    }

    /** Writes the mutable columns, bumps {@code version}; throws on a stale {@code o.version()}. */
    public Order update(Order o) {
        int rows = jdbc.sql("update orders set state = :state, filled_size = :filledSize, avg_fill_price = :avgFillPrice, "
                        + "fees = :fees, venue_order_id = :venueOrderId, reason = :reason, updated_at = :updatedAt, "
                        + "version = version + 1 where id = :id and version = :version")
                .param("state", o.state().name())
                .param("filledSize", o.filledSize())
                .param("avgFillPrice", o.avgFillPrice())
                .param("fees", o.fees())
                .param("venueOrderId", o.venueOrderId())
                .param("reason", o.reason())
                .param("updatedAt", utc(o.updatedAt()))
                .param("id", o.id())
                .param("version", o.version())
                .update();
        if (rows == 0) {
            throw new OptimisticLockingFailureException("order " + o.id() + " changed concurrently (version " + o.version() + ")");
        }
        return new Order(o.id(), o.clientOrderId(), o.idempotencyKey(), o.venue(), o.instrumentId(), o.symbol(),
                o.side(), o.type(), o.size(), o.limitPrice(), o.timeInForce(), o.reduceOnly(), o.state(),
                o.filledSize(), o.avgFillPrice(), o.fees(), o.venueOrderId(), o.source(), o.reason(), o.createdAt(),
                o.updatedAt(), o.version() + 1);
    }

    /** Records risk-service's decision id; call inside the transaction of the APPROVED/RISK_REJECTED transition. */
    public void setRiskDecision(UUID orderId, UUID decisionId) {
        jdbc.sql("update orders set risk_decision_id = :decisionId where id = :id")
                .param("decisionId", decisionId)
                .param("id", orderId)
                .update();
    }

    public Optional<UUID> riskDecisionId(UUID orderId) {
        return jdbc.sql("select risk_decision_id from orders where id = :id")
                .param("id", orderId)
                .query((rs, row) -> rs.getObject("risk_decision_id", UUID.class))
                .optional();
    }

    public Optional<UUID> signalId(UUID orderId) {
        return jdbc.sql("select signal_id from orders where id = :id")
                .param("id", orderId)
                .query((rs, row) -> rs.getObject("signal_id", UUID.class))
                .optional();
    }

    public void addTransition(UUID orderId, OrderTransition t) {
        jdbc.sql("insert into order_transition (order_id, from_state, to_state, reason, at) "
                        + "values (:orderId, :from, :to, :reason, :at)")
                .param("orderId", orderId)
                .param("from", t.from() == null ? null : t.from().name())
                .param("to", t.to().name())
                .param("reason", t.reason())
                .param("at", utc(t.at()))
                .update();
    }

    public List<OrderTransition> transitions(UUID orderId) {
        return jdbc.sql("select from_state, to_state, reason, at from order_transition where order_id = :id order by id")
                .param("id", orderId)
                .query((rs, row) -> new OrderTransition(
                        rs.getString("from_state") == null ? null : OrderState.valueOf(rs.getString("from_state")),
                        OrderState.valueOf(rs.getString("to_state")),
                        rs.getString("reason"),
                        rs.getObject("at", OffsetDateTime.class).toInstant()))
                .list();
    }

    /** Newest first; all states when {@code state} is empty. */
    public List<Order> findByState(Optional<OrderState> state, int limit) {
        return jdbc.sql(SELECT + "where (cast(:state as text) is null or o.state = :state) "
                        + "order by o.created_at desc, o.id limit :limit")
                .param("state", state.map(Enum::name).orElse(null))
                .param("limit", limit)
                .query(OrderRepository::map)
                .list();
    }

    /** Orders resting on the venue that can still fill (ACKED or PARTIALLY_FILLED). */
    public List<Order> findOpen(Venue venue) {
        return jdbc.sql(SELECT + "where o.venue = :venue and o.state in ('ACKED', 'PARTIALLY_FILLED') "
                        + "order by o.created_at, o.id")
                .param("venue", venue.name())
                .query(OrderRepository::map)
                .list();
    }

    /** {@link #findOpen} plus CANCEL_REQUESTED: orders the open-order checker must still resolve at the venue. */
    public List<Order> findCheckable(Venue venue) {
        return jdbc.sql(SELECT + "where o.venue = :venue and o.state in ('ACKED', 'PARTIALLY_FILLED', 'CANCEL_REQUESTED') "
                        + "order by o.created_at, o.id")
                .param("venue", venue.name())
                .query(OrderRepository::map)
                .list();
    }

    /** Every order that is not in a terminal state (NEW to CANCEL_REQUESTED, UNKNOWN included), on any venue. */
    public List<Order> findNotTerminal() {
        List<String> states = Arrays.stream(OrderState.values()).filter(s -> !s.isTerminal()).map(Enum::name).toList();
        return jdbc.sql(SELECT + "where o.state in (:states) order by o.created_at, o.id")
                .param("states", states)
                .query(OrderRepository::map)
                .list();
    }

    /** Orders in {@code state} whose last change is before {@code cutoff}, oldest first (reconciler). */
    public List<Order> findByStateBefore(OrderState state, java.time.Instant cutoff) {
        return jdbc.sql(SELECT + "where o.state = :state and o.updated_at < :cutoff order by o.updated_at, o.id")
                .param("state", state.name())
                .param("cutoff", utc(cutoff))
                .query(OrderRepository::map)
                .list();
    }

    /** All orders in {@code state}, oldest change first (reconciler). */
    public List<Order> findAllByState(OrderState state) {
        return jdbc.sql(SELECT + "where o.state = :state order by o.updated_at, o.id")
                .param("state", state.name())
                .query(OrderRepository::map)
                .list();
    }

    public Optional<Order> findByVenueOrderId(Venue venue, String venueOrderId) {
        return jdbc.sql(SELECT + "where o.venue = :venue and o.venue_order_id = :venueOrderId")
                .param("venue", venue.name())
                .param("venueOrderId", venueOrderId)
                .query(OrderRepository::map)
                .optional();
    }

    private static Order map(ResultSet rs, int row) throws SQLException {
        return new Order(
                rs.getObject("id", UUID.class),
                rs.getString("client_order_id"),
                rs.getString("idempotency_key"),
                Venue.valueOf(rs.getString("venue")),
                rs.getLong("instrument_id"),
                rs.getString("symbol"),
                Side.valueOf(rs.getString("side")),
                OrderType.valueOf(rs.getString("type")),
                rs.getLong("size"),
                rs.getBigDecimal("limit_price"),
                TimeInForce.valueOf(rs.getString("time_in_force")),
                rs.getBoolean("reduce_only"),
                OrderState.valueOf(rs.getString("state")),
                rs.getLong("filled_size"),
                rs.getBigDecimal("avg_fill_price"),
                rs.getBigDecimal("fees"),
                rs.getString("venue_order_id"),
                OrderSource.valueOf(rs.getString("source")),
                rs.getString("reason"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                rs.getLong("version"));
    }

    private static OffsetDateTime utc(java.time.Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
