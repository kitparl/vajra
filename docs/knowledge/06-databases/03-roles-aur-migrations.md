---
title: DB roles aur migrations hamare project me
topic: databases
level: beginner
---

# DB roles aur migrations hamare project me

## Chhota example
Ek building me 5 flats hain. Sabke paas **apne flat ki chaabi** hai, doosre flat ki nahi. Common lobby (`public`) me koi apna saaman nahi rakh sakta.
Hamara Postgres wahi building hai: har service ek flat (schema), aur uska DB role uski chaabi.

| Flat (schema) | Chaabi (role) | Kiska |
|---|---|---|
| `core` | `core_svc` | core-service (`brahma/`) |
| `risk` | `risk_svc` | risk-service (`maryada/`) |
| `gateway` | `gateway_svc` | gateway-bff (`dvara/`) |
| `research` | `research_svc` | research-service (`manthan/`) |
| `signal` | `signal_svc` | signal-service (`shankha/`) |

## Ye bante kaise hain?
`infra/postgres/init/01-roles-and-schemas.sh` pehli baar Postgres start hone par flats aur chaabiyan bana deta hai. Dobara chalao to bhi kuch nahi tootta.
Ek test (`infra/postgres/test/role-isolation.sh`) har baar check karta hai ki koi chaabi doosre flat me na lage. Humne jaan-boojhkar ek galat chaabi di thi, test ne turant pakda.

## Migration kya hai?
Table banana ya badalna = **flat me renovation ka likhit order**. Har order ek file hai, number ke saath, aur ek register (`flyway_schema_history` / `alembic_version`) me likha jaata hai ki kaunsa order ho chuka.
- Java services: Flyway (`V1__baseline.sql`)
- Python services: Alembic (`0001_baseline.py`)

## LifeOS se seekha sabak
Agar 2 mazdoor ek hi waqt ek hi deewar todne lagein to gadbad hogi. LifeOS me 2 workers ek saath migration chalate the aur app atak jaati thi.
Isliye hamari Python services me migration **pehle alag se** chalti hai, uske baad server start hota hai.

## Password bhool gaye to?
Service start hi nahi hogi aur saaf bataegi: `Missing required environment variable(s): DB_PASSWORD`. Aadha-adhura chalne se behtar hai turant rukna.

Formal record: `../0016-database-roles-and-migrations.md`.
