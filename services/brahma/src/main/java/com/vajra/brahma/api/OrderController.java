package com.vajra.brahma.api;

import com.vajra.brahma.oms.Order;
import com.vajra.brahma.oms.OrderDetails;
import com.vajra.brahma.oms.OrderService;
import com.vajra.brahma.oms.OrderState;
import com.vajra.brahma.oms.PlaceOrderResult;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class OrderController {

    private final OrderService orders;

    OrderController(OrderService orders) {
        this.orders = orders;
    }

    /** 201 for a new order, 200 when the same idempotency key already created one. */
    @PostMapping("/api/v1/orders")
    ResponseEntity<Order> place(
            @RequestHeader("Idempotency-Key") String idempotencyKey, @Valid @RequestBody PlaceOrderRequest request) {
        PlaceOrderResult result = orders.place(request.toIntent(idempotencyKey));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.order());
    }

    @PostMapping("/api/v1/orders/{id}/cancel")
    Order cancel(@PathVariable UUID id) {
        return orders.cancel(id);
    }

    /** Newest 100 orders, optionally only those in {@code state}. */
    @GetMapping("/api/v1/orders")
    List<Order> list(@RequestParam Optional<OrderState> state) {
        return orders.list(state);
    }

    @GetMapping("/api/v1/orders/{id}")
    OrderDetails details(@PathVariable UUID id) {
        return orders.details(id).orElseThrow(() -> ApiException.notFound("order not found: " + id));
    }
}
