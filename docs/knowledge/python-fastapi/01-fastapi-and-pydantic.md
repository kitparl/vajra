---
title: FastAPI aur Pydantic
topic: python-fastapi
level: beginner
---

# FastAPI aur Pydantic

## 1. Python kyun?
Data science aur ML ki libraries (pandas, numpy, scikit-learn, XGBoost, PyTorch) Python me sabse acchi hain. Backtest, feature banana, model train karna sab inhi se hota hai.

**Example:** Kitchen me jaise tandoor ke liye alag aur dosa ke liye tawa alag, waise ML ke liye Python hi sahi tool hai.

## 2. FastAPI kya hai?
Python me web service banane ka tez aur aasaan framework. Java ke Spring Boot jaisa kaam, par Python style me.

```python
from fastapi import FastAPI

app = FastAPI()

@app.get("/health")
def health():
    return {"status": "ok"}
```
Isse `GET /health` chalne lagta hai. Aur FastAPI khud `/docs` page bana deta hai jahan API test kar sakte ho.

## 3. Pydantic (data ki jaanch)
Bahar se aaya data hamesha **bharosemand nahi** hota. Pydantic check karta hai ki data sahi shape me hai.

```python
from pydantic import BaseModel, Field

class Signal(BaseModel):
    symbol: str
    side: str                    # "BUY" ya "SELL"
    size: float = Field(gt=0)    # 0 se bada hona chahiye
    confidence: float = Field(ge=0, le=1)

# Galat data -> error:
Signal(symbol="BTCUSD", side="BUY", size=-5, confidence=0.7)
# ValidationError: size must be greater than 0
```
**Example:** Airport par security check. Kuch bhi bina jaanch andar nahi jaata.

## 4. Hamare project me
- `signal-service`: strategy chalata hai, model se signal banata hai, `signal.generated` event bhejta hai.
- `research-service`: backtest, training, model registry (dekho `../backtesting-and-learning/`).
- Migrations: **Alembic** (Python ka Flyway).
- Version: Python 3.14, FastAPI ka **exact version pin** (0.x me minor version breaking ho sakta hai).

## 5. Sabse zaroori rule
Python services **order place ya modify nahi karti.** Wo sirf signal ya report dete hain. Order sirf `core-service` aur `risk-service` ke raaste se jaata hai (Java).

## 6. Common galtiyan
- **Float se paise ka hisaab:** price ke liye `Decimal` use karo jahan exact chahiye.
- **Backtest aur live me alag code:** same strategy code dono jagah chalna chahiye.
- **Library ke version pin na karna:** kal naya version aaya aur sab tut gaya.

## Yaad rakhne wali baatein
- Python = ML/research. FastAPI = uski web service.
- Pydantic = data ki jaanch.
- Python service order nahi bhejti, sirf signal deti hai.
