"""ETL de tipos de cambio de Costa Rica (API del Ministerio de Hacienda).

Modo diario (por defecto): guarda el tipo de cambio de hoy (USD y EUR).
Modo backfill: carga el histórico de USD desde una fecha dada.

Uso:
    python etl.py                          # carga el dato de hoy
    python etl.py --backfill 2020-01-01    # carga histórico desde esa fecha
    python etl.py --dry-run                # imprime sin escribir a la BD

Requiere la variable de entorno DATABASE_URL (formato postgres://...).
"""
import argparse
import datetime as dt
import json
import os
import statistics
import sys
import time
import urllib.request

API = "https://api.hacienda.go.cr/indicadores/tc"

DDL = """
CREATE TABLE IF NOT EXISTS exchange_rates (
    fecha       date NOT NULL,
    moneda      varchar(3) NOT NULL,
    compra      numeric(10,2),
    venta       numeric(10,2),
    actualizado timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (fecha, moneda)
);
"""

UPSERT = """
INSERT INTO exchange_rates (fecha, moneda, compra, venta)
VALUES (%s, %s, %s, %s)
ON CONFLICT (fecha, moneda)
DO UPDATE SET compra = EXCLUDED.compra,
              venta = EXCLUDED.venta,
              actualizado = now()
"""

DDL_FORECAST = """
CREATE TABLE IF NOT EXISTS forecasts (
    fecha    date NOT NULL,
    moneda   varchar(3) NOT NULL,
    venta    numeric(10,2) NOT NULL,
    generado timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (fecha, moneda)
);
"""

DDL_NOTICIAS = """
CREATE EXTENSION IF NOT EXISTS vector;
CREATE TABLE IF NOT EXISTS noticias (
    enlace    text PRIMARY KEY,
    fecha     date,
    titulo    text NOT NULL,
    fuente    text,
    embedding vector(768)
);
"""

RSS_NOTICIAS = ("https://news.google.com/rss/search?"
                "q=%22tipo+de+cambio%22+OR+d%C3%B3lar+OR+econom%C3%ADa+costa+rica"
                "&hl=es-419&gl=CR&ceid=CR:es-419")


def fetch(url, retries=4, parse=lambda b: b):
    # ponytail: la API a veces devuelve una página HTML de error; reintento con espera exponencial
    for intento in range(retries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "pura-data-etl/1.0"})
            with urllib.request.urlopen(req, timeout=30) as resp:
                return parse(resp.read())
        except (json.JSONDecodeError, OSError) as e:
            if intento == retries - 1:
                raise SystemExit(f"API no disponible tras {retries} intentos: {e}")
            time.sleep(2 ** (intento + 1))


def fetch_json(url):
    return fetch(url, parse=lambda b: json.loads(b.decode()))


def rows_hoy():
    """Tipo de cambio actual: USD (compra/venta) y EUR (solo venta en colones)."""
    data = fetch_json(API)
    dolar = data["dolar"]
    rows = [(
        dt.date.fromisoformat(dolar["venta"]["fecha"]),
        "USD",
        dolar["compra"]["valor"],
        dolar["venta"]["valor"],
    )]
    euro = data.get("euro")
    if euro and euro.get("colones"):
        rows.append((dt.date.fromisoformat(euro["fecha"]), "EUR", None, euro["colones"]))
    return rows


def rows_backfill(desde):
    """Histórico de USD por tramos anuales (rangos grandes fallan en la API)."""
    hoy = dt.date.today()
    rows = []
    inicio = desde
    while inicio <= hoy:
        fin = min(dt.date(inicio.year, 12, 31), hoy)
        url = f"{API}/dolar/historico?d={inicio}&h={fin}"
        for item in fetch_json(url):
            fecha = dt.date.fromisoformat(item["fecha"][:10])
            rows.append((fecha, "USD", item["compra"], item["venta"]))
        print(f"  {inicio} a {fin}: acumulados {len(rows)} registros")
        inicio = dt.date(inicio.year + 1, 1, 1)
        time.sleep(1)  # no saturar la API
    return rows


def noticias_rss():
    """Titulares económicos de Costa Rica desde Google News RSS."""
    import xml.etree.ElementTree as ET
    from email.utils import parsedate_to_datetime
    items = []
    for item in ET.fromstring(fetch(RSS_NOTICIAS)).iter("item"):
        titulo, enlace = item.findtext("title"), item.findtext("link")
        pub = item.findtext("pubDate")
        if titulo and enlace:
            items.append((enlace, parsedate_to_datetime(pub).date() if pub else None,
                          titulo, item.findtext("source")))
    return items[:30]


def guardar_noticias(items):
    import psycopg
    with psycopg.connect(os.environ["DATABASE_URL"]) as conn:
        conn.execute(DDL_NOTICIAS)
        with conn.cursor() as cur:
            cur.executemany(
                "INSERT INTO noticias (enlace, fecha, titulo, fuente) VALUES (%s, %s, %s, %s) "
                "ON CONFLICT (enlace) DO NOTHING", items)
    print(f"Noticias procesadas: {len(items)} (el backend calcula sus embeddings).")


def guardar(rows):
    import psycopg
    with psycopg.connect(os.environ["DATABASE_URL"]) as conn:
        conn.execute(DDL)
        with conn.cursor() as cur:
            cur.executemany(UPSERT, rows)
        proyectar(conn)
    print(f"Guardados {len(rows)} registros en la base de datos.")


def proyectar(conn, dias=7):
    """Proyección de venta del dólar: regresión lineal sobre las últimas 30 observaciones."""
    obs = conn.execute(
        "SELECT fecha, venta FROM exchange_rates WHERE moneda = 'USD' AND venta IS NOT NULL "
        "ORDER BY fecha DESC LIMIT 30").fetchall()
    if len(obs) < 10:
        return
    obs.reverse()
    x = [f.toordinal() for f, _ in obs]
    y = [float(v) for _, v in obs]
    # ponytail: regresión lineal de stdlib; un modelo con estacionalidad si algún día hace falta
    pendiente, intercepto = statistics.linear_regression(x, y)
    ultima = obs[-1][0]
    conn.execute(DDL_FORECAST)
    conn.execute("DELETE FROM forecasts WHERE moneda = 'USD'")
    with conn.cursor() as cur:
        cur.executemany(
            "INSERT INTO forecasts (fecha, moneda, venta) VALUES (%s, 'USD', %s)",
            [(ultima + dt.timedelta(days=i),
              round(intercepto + pendiente * (ultima.toordinal() + i), 2))
             for i in range(1, dias + 1)])
    print(f"Proyección de {dias} días recalculada.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--backfill", type=dt.date.fromisoformat, metavar="YYYY-MM-DD",
                        help="cargar histórico de USD desde esta fecha")
    parser.add_argument("--dry-run", action="store_true", help="imprimir sin escribir a la BD")
    args = parser.parse_args()

    rows = rows_backfill(args.backfill) if args.backfill else rows_hoy()
    if not rows:
        sys.exit("La API no devolvió datos.")

    if args.dry_run:
        for r in rows[:5]:
            print(r)
        print(f"... total {len(rows)} registros (dry-run, no se escribió nada)")
    else:
        guardar(rows)
        if not args.backfill:
            guardar_noticias(noticias_rss())


if __name__ == "__main__":
    main()
