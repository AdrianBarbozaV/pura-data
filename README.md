# pura-data 🇨🇷

Asistente de datos económicos de Costa Rica: un pipeline recolecta el tipo de cambio
oficial todos los días de forma automática, un dashboard lo grafica, y una IA local
responde preguntas en lenguaje natural sobre los datos.

## Arquitectura

```
GitHub Actions (cron diario)
   → ETL en Python (API del Ministerio de Hacienda)
   → PostgreSQL + pgvector (Neon, capa gratuita)
   → Spring Boot + Spring AI (REST API + RAG)
   → Ollama (LLM local: llama3.2)
   → Angular (dashboard + chat)
```

## Stack

- **ETL:** Python 3 (stdlib + psycopg), GitHub Actions como scheduler
- **Base de datos:** PostgreSQL (Neon free tier) — histórico + embeddings (pgvector)
- **Backend:** Java 21, Spring Boot 3, Spring JDBC, Spring AI
- **Frontend:** Angular
- **IA:** Ollama con llama3.2 (100% local, sin costos de API)

## Endpoints

| Método | Ruta | Descripción |
|--------|------|-------------|
| GET | `/api/rates?moneda=USD&from=2025-01-01&to=2025-12-31` | Histórico por moneda y rango |
| GET | `/api/rates/latest` | Último valor de cada moneda |

## Cómo correrlo

### ETL

```bash
cd etl
pip install -r requirements.txt
python etl.py --dry-run                # probar sin base de datos
set DATABASE_URL=postgres://...        # Neon o Postgres local
python etl.py --backfill 2015-01-01    # carga histórica inicial (una vez)
python etl.py                          # carga del día
```

En GitHub, el workflow `ETL diario` corre solo cada día a las 12:30 pm (hora CR).
Requiere el secret `DATABASE_URL` en *Settings → Secrets and variables → Actions*.

### Backend

```bash
cd backend
set JDBC_DATABASE_URL=jdbc:postgresql://host/db?user=X&password=Y&sslmode=require
mvnw spring-boot:run
# http://localhost:8080/api/rates/latest
```

## Plan de trabajo (10 días)

- [x] **Día 1** — Repo, ETL con reintentos y upsert, esquema de BD, API REST, workflow de GitHub Actions
- [ ] **Día 2** — Cuenta en Neon, backfill histórico, activar el cron, probar API con datos reales
- [ ] **Días 3–4** — Dashboard Angular: gráfica de serie de tiempo, selector de rango, último valor
- [ ] **Días 5–7** — Capa RAG: pgvector + Spring AI + Ollama, endpoint `/api/chat` y UI de chat
- [ ] **Días 8–9** — Manejo de errores, pruebas básicas, CI de build
- [ ] **Día 10** — README final con diagrama, screenshots y demo en GIF
