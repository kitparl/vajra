# Architecture basics (System ka naksha)

**Level:** beginner to intermediate
**Kisliye:** samajhna ki hamara project kin-kin hisso (services) me baata hai, wo ek doosre se kaise baat karte hain, aur kiska data kiska hai.

## Files
| File | Topic |
|---|---|
| `01-service-monolith-microservice.md` | Service kya hai, monolith vs microservices, hamari 5 services |
| `02-api-grpc-and-data-ownership.md` | API, REST vs gRPC, sync vs async, data ownership rule |
| `03-repo-folder-structure.md` | Repo ke folders, kaunsi service kahan hai |

## Ek line me saar
Hamare paas **5 backend services** hain: 3 Java Spring Boot (`core`, `risk`, `gateway-bff`) aur 2 Python FastAPI (`research`, `signal`), plus ek Angular UI. **Backend sirf Java aur Python me hai, NestJS nahi** (formal record: `../0011-backend-java-and-python-only.md`).
