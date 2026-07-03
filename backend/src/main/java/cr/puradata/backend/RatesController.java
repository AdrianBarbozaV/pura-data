package cr.puradata.backend;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

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
}
