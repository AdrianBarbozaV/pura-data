package cr.puradata.backend;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rates")
@CrossOrigin
public class RatesController {

    public record Rate(LocalDate fecha, String moneda, BigDecimal compra, BigDecimal venta) {}

    private static final RowMapper<Rate> MAPPER = (rs, i) -> new Rate(
            rs.getObject("fecha", LocalDate.class),
            rs.getString("moneda"),
            rs.getBigDecimal("compra"),
            rs.getBigDecimal("venta"));

    private final JdbcTemplate jdbc;

    public RatesController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public List<Rate> list(
            @RequestParam(defaultValue = "USD") String moneda,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate desde = from != null ? from : LocalDate.of(1990, 1, 1);
        LocalDate hasta = to != null ? to : LocalDate.now();
        return jdbc.query(
                "SELECT fecha, moneda, compra, venta FROM exchange_rates "
                        + "WHERE moneda = ? AND fecha BETWEEN ? AND ? ORDER BY fecha",
                MAPPER, moneda.toUpperCase(), desde, hasta);
    }

    @GetMapping("/latest")
    public List<Rate> latest() {
        return jdbc.query(
                "SELECT DISTINCT ON (moneda) fecha, moneda, compra, venta "
                        + "FROM exchange_rates ORDER BY moneda, fecha DESC",
                MAPPER);
    }

    /** Estadísticas de venta sobre las últimas N observaciones disponibles. */
    @GetMapping("/stats")
    public Map<String, Object> stats(
            @RequestParam(defaultValue = "USD") String moneda,
            @RequestParam(defaultValue = "30") int observaciones) {
        return jdbc.queryForMap(
                "WITH ultimos AS ("
                        + "  SELECT fecha, venta FROM exchange_rates "
                        + "  WHERE moneda = ? AND venta IS NOT NULL "
                        + "  ORDER BY fecha DESC LIMIT ?) "
                        + "SELECT min(venta) AS minimo, max(venta) AS maximo, "
                        + "  round(avg(venta), 2) AS promedio, "
                        + "  round((SELECT venta FROM ultimos ORDER BY fecha DESC LIMIT 1) "
                        + "      - (SELECT venta FROM ultimos ORDER BY fecha ASC LIMIT 1), 2) AS variacion, "
                        + "  count(*) AS observaciones "
                        + "FROM ultimos",
                moneda.toUpperCase(), observaciones);
    }

    @GetMapping("/forecast")
    public List<Rate> forecast(@RequestParam(defaultValue = "USD") String moneda) {
        return jdbc.query(
                "SELECT fecha, moneda, NULL AS compra, venta FROM forecasts "
                        + "WHERE moneda = ? ORDER BY fecha",
                MAPPER, moneda.toUpperCase());
    }
}
