---
title: Spring Boot basics
topic: java-spring-boot
level: beginner
---

# Spring Boot basics

## 1. Spring Boot kya hai?
Java me web service (jo requests sunti hai aur jawab deti hai) banane ka **ready-made toolkit**.

**Example:** Ghar banana hai. Aap khud eent, cement, bijli ki wiring sab shuru se banao, ya ready-made flat lo jisme bijli-paani pehle se laga ho? Spring Boot ready-made flat hai: server, JSON, database connection sab pehle se set.

## 2. Layers (teen parts)
Ek acchi Spring Boot service me kaam teen layers me baata jaata hai:

| Layer | Kaam | Restaurant example |
|---|---|---|
| **Controller** | Bahar se request leta hai (REST) | Waiter: order leta hai |
| **Service** | Asli business logic | Rasoiya: khana banata hai |
| **Repository** | Database se baat | Store-room incharge: saman nikalta hai |

Kaam ka flow: Controller → Service → Repository → Database.

## 3. Chhota example (order dekhna)
```java
// Controller: request leta hai
@RestController
@RequestMapping("/orders")
class OrderController {
    private final OrderService service;
    OrderController(OrderService service) { this.service = service; }

    @GetMapping("/{id}")
    OrderDto get(@PathVariable String id) {
        return service.findOrder(id);
    }
}

// Service: logic
@Service
class OrderService {
    private final OrderRepository repo;
    OrderService(OrderRepository repo) { this.repo = repo; }

    OrderDto findOrder(String id) {
        return repo.findById(id)
                   .map(OrderDto::from)
                   .orElseThrow(() -> new OrderNotFound(id));
    }
}

// Repository: database
interface OrderRepository extends JpaRepository<OrderEntity, String> {}
```
Samjho: `GET /orders/42` aaya → Controller ne Service ko bola → Service ne Repository se order nikala → wapas bheja.

## 4. Dependency Injection (DI) ek line me
Upar `OrderController(OrderService service)` me hum `OrderService` khud nahi banate; Spring **bana ke de deta hai**. Isse test likhna aasaan hota hai, kyunki test me nakli (fake) service de sakte ho.

## 5. Project me kahan?
- `core-service`: orders, positions, connectors. Yahan **idempotency** aur **state machine** (dekho `../08-order-lifecycle/`).
- `risk-service`: limits check. Deterministic, ML nahi (dekho `../02-risk-management/`).
- `gateway-bff`: UI ke liye REST + WebSocket.

## 6. Migrations
Java services me database badlav **Flyway** se hote hain. Har badlav ek numbered SQL file (`V1__create_orders.sql`) hoti hai. (dekho `../06-databases/`)

## 7. Common galtiyan
- **Controller me business logic likhna.** Logic Service me, Controller patla rakho.
- **Paisa `double` me rakhna.** Paisa/price ke liye `BigDecimal` use karo, `double` me rounding galtiyan hoti hain. Example: `0.1 + 0.2` double me `0.30000000000000004` aata hai.
- **Doosri service ka table padhna.** Data ownership todta hai.

## Yaad rakhne wali baatein
- Controller (bahar) → Service (logic) → Repository (DB).
- Paise ke liye `BigDecimal`.
- Controller patla, Service me logic.
