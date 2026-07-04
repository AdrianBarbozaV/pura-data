package cr.puradata.backend;

import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@CrossOrigin
public class ChatController {

    private final ChatClient chat;
    private final JdbcTemplate jdbc;

    public ChatController(ChatClient.Builder builder, JdbcTemplate jdbc) {
        this.chat = builder.build();
        this.jdbc = jdbc;
    }

    public record Pregunta(String pregunta) {}

    @PostMapping("/api/chat")
    public Map<String, String> responder(@RequestBody Pregunta p) {
        String respuesta = chat.prompt()
                .system("""
                        Eres el asistente de pura-data, una app de tipos de cambio de Costa Rica
                        con datos oficiales del Ministerio de Hacienda.
                        Responde en español, breve y directo, usando ÚNICAMENTE los datos siguientes.
                        Si la pregunta no se puede responder con estos datos, dilo claramente.

                        %s""".formatted(contexto()))
                .user(p.pregunta())
                .call()
                .content();
        return Map.of("respuesta", respuesta);
    }

    /* ponytail: para datos estructurados el retrieval son consultas SQL al contexto
       del prompt; pgvector/embeddings cuando se agreguen fuentes de texto libre */
    private String contexto() {
        var sb = new StringBuilder("Últimos valores (en colones):\n");
        jdbc.queryForList("SELECT DISTINCT ON (moneda) fecha, moneda, compra, venta "
                        + "FROM exchange_rates ORDER BY moneda, fecha DESC")
                .forEach(r -> sb.append("  %s %s: compra=%s venta=%s%n"
                        .formatted(r.get("fecha"), r.get("moneda"), r.get("compra"), r.get("venta"))));

        sb.append("Dólar en los últimos 90 días: ");
        sb.append(jdbc.queryForMap("SELECT min(venta) AS minimo, max(venta) AS maximo, "
                + "round(avg(venta), 2) AS promedio FROM exchange_rates "
                + "WHERE moneda = 'USD' AND fecha >= current_date - 90"));

        sb.append("\nPromedio mensual de venta del dólar en el último año:\n");
        jdbc.queryForList("SELECT to_char(date_trunc('month', fecha), 'YYYY-MM') AS mes, "
                        + "round(avg(venta), 2) AS promedio FROM exchange_rates "
                        + "WHERE moneda = 'USD' AND fecha >= current_date - 365 "
                        + "GROUP BY 1 ORDER BY 1")
                .forEach(r -> sb.append("  %s: %s%n".formatted(r.get("mes"), r.get("promedio"))));
        return sb.toString();
    }
}
