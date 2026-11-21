---
title: gateway-bff (Spring Boot): REST, WebSocket, scheduler, notifier
topic: java-spring-boot
level: intermediate
---

# `gateway-bff` kaise banega (Spring Boot)

Pehle ye NestJS me socha gaya tha. Ab faisla: **Java Spring Boot** (dekho `../0011-backend-java-and-python-only.md`).

## 1. gateway-bff ka kaam
1. **REST API** UI ke liye (watchlist, alerts, settings).
2. **Live WebSocket** (rate, positions, orders live screen par).
3. **Notifier** (Telegram/email alert bhejna).
4. **Scheduler** (market session, token refresh, din ke end ke jobs).

Ye **order/fee/position ka hisaab nahi** karta. Kisi user ne UI se order diya to gateway use core/risk ke API ko aage bhej deta hai.

## 2. REST: UI ko data dena
```java
@RestController
@RequestMapping("/api/watchlists")
class WatchlistController {
    private final WatchlistService service;
    WatchlistController(WatchlistService service) { this.service = service; }

    @GetMapping
    List<WatchlistDto> list() { return service.listForCurrentUser(); }
}
```
Watchlist `gateway` schema me rehti hai, jo gateway ki apni hai.

## 3. WebSocket: live data
**Matlab:** ek baar connection khulta hai, aur server **khud** naya data bhejta rehta hai. UI ko baar-baar poochna nahi padta.

**Example:** REST = baar-baar phone karke poochna "score kya hua?". WebSocket = commentary radio chalu rakhna, jo khud bolta rehta hai.

Flow:
1. `core-service` rate/fill ka event Kafka par daalta hai.
2. `gateway-bff` Kafka se wo event sunta hai.
3. Gateway use connected UI users ko WebSocket se bhej deta hai. Isko **fan-out** kehte hain (ek se bahuton ko).

Spring me ye **Spring WebSocket (STOMP)** ya SSE se hota hai. Ek chhoti jhalak:
```java
@Component
class PriceFanout {
    private final SimpMessagingTemplate ws;
    PriceFanout(SimpMessagingTemplate ws) { this.ws = ws; }

    // Kafka se tick aaya -> UI ko bhejo
    void onTick(TickEvent tick) {
        ws.convertAndSend("/topic/ticks/" + tick.symbol(), tick);
    }
}
```

## 4. Scheduler: waqt par kaam
```java
@Component
class DailyJobs {
    @Scheduled(cron = "0 30 23 * * *")   // roz raat 11:30 par
    void endOfDayReport() { /* summary banao */ }
}
```
**Dhyaan:** agar gateway ke 2 instance chale to dono ek hi job chalayenge. Isliye jahan zaroori ho wahan lock (jaise ShedLock) lagate hain.

## 5. Notifier
Alert aaya (`alert.raised` event) → gateway Telegram/email bhejta hai → `notification log` gateway schema me likhta hai.

## 6. Security
- UI users ka login/token gateway sambhalta hai.
- Gateway se core/risk ko service-to-service auth ke saath call jaati hai.
- Exchange API keys gateway ke paas **kabhi** nahi hoti.

## Yaad rakhne wali baatein
- Gateway = UI ka assistant (REST + WebSocket + notifier + scheduler).
- Isme order/position ka logic nahi.
- WebSocket = server khud data bhejta hai.
- Scheduler multiple instance me double na chale, iska dhyaan.
