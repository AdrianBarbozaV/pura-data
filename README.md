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
- **IA:** Ollama 100% local — llama3.2 (chat) y nomic-embed-text (embeddings); búsqueda semántica de noticias con pgvector

## Endpoints

| Método | Ruta | Descripción |
|--------|------|-------------|
| GET | `/api/rates?moneda=USD&from=2025-01-01&to=2025-12-31` | Histórico por moneda y rango |
| GET | `/api/rates/latest` | Último valor de cada moneda |
| GET | `/api/rates/stats?moneda=USD&observaciones=30` | Mín/máx/promedio/variación de las últimas N observaciones |
| GET | `/api/rates/forecast` | Proyección a 7 días (regresión lineal recalculada por el pipeline en cada corrida) |
| GET | `/api/news` | Últimos titulares económicos de Costa Rica (Google News RSS) |
| POST | `/api/chat` | Pregunta en lenguaje natural; RAG con pgvector sobre noticias + datos de tipo de cambio |

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

Para el chat con IA se necesita [Ollama](https://ollama.com) corriendo con el modelo:

```bash
ollama pull llama3.2
```

### Frontend

```bash
cd frontend
npm install
npx ng serve
# http://localhost:4200
```

## Plan de trabajo (10 días)

- [x] **Día 1** — Repo, ETL con reintentos y upsert, esquema de BD, API REST, workflow de GitHub Actions
- [x] **Día 2** — Cuenta en Neon, backfill histórico (2,527 registros desde 2019), API probada con datos reales
- [x] **Días 3–4** — Dashboard Angular: gráfica de serie de tiempo, selector de rango, último valor
- [x] **Días 5–7** — Asistente IA: Spring AI + Ollama, endpoint `/api/chat` y UI de chat (retrieval por SQL; pgvector queda para fuentes de texto libre)
- [x] **Días 8–9** — CI con pruebas de backend y build de frontend en GitHub Actions; cron del ETL verificado en la nube
- [x] **Extra** — Proyección del dólar a 7 días (regresión lineal en el pipeline), endpoint de estadísticas y línea de proyección en el dashboard
- [x] **Extra** — RAG con pgvector: el ETL ingiere titulares económicos (Google News RSS), el backend calcula embeddings con Ollama y el chat recupera los titulares más relevantes por similitud semántica
- [ ] **Pendiente** — README final con screenshots y demo en GIF
