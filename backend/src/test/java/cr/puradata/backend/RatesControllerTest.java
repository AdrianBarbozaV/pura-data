package cr.puradata.backend;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RatesController.class)
class RatesControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private JdbcTemplate jdbc;

    private final RatesController.Rate rate = new RatesController.Rate(
            LocalDate.of(2026, 7, 3), "USD", new BigDecimal("450.98"), new BigDecimal("456.09"));

    @Test
    void latestDevuelveUltimosValores() throws Exception {
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of(rate));
        mvc.perform(get("/api/rates/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].moneda").value("USD"))
                .andExpect(jsonPath("$[0].venta").value(456.09));
    }

    @Test
    void listFiltraPorMonedaYRango() throws Exception {
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any()))
                .thenReturn(List.of(rate));
        mvc.perform(get("/api/rates?moneda=usd&from=2026-01-01&to=2026-12-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fecha").value("2026-07-03"));
    }

    @Test
    void fechaInvalidaDevuelve400() throws Exception {
        mvc.perform(get("/api/rates?from=no-es-fecha"))
                .andExpect(status().isBadRequest());
    }
}
