package cr.puradata.backend;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@CrossOrigin
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatClient chat;
    private final EmbeddingModel embeddings;
    private final JdbcTemplate jdbc;

    public ChatController(ChatClient.Builder builder, EmbeddingModel embeddings, JdbcTemplate jdbc) {
        this.chat = builder.build();
        this.embeddings = embeddings;
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

                        %s%s""".formatted(contexto(), contextoNoticias(p.pregunta())))
                .user(p.pregunta())
                .call()
                .content();
        return Map.of("respuesta", respuesta);
    }

    @GetMapping("/api/news")
    public List<Map<String, Object>> noticias() {
        return jdbc.queryForList(
                "SELECT fecha, titulo, fuente, enlace FROM noticias "
                        + "ORDER BY fecha DESC NULLS LAST LIMIT 10");
    }

    /** Al arrancar, calcula los embeddings de las noticias que el ETL dejó pendientes. */
    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void embeberPendientes() {
        try {
            var pendientes = jdbc.queryForList("SELECT enlace, titulo FROM noticias WHERE embedding IS NULL");
            for (var n : pendientes) {
                jdbc.update("UPDATE noticias SET embedding = ?::vector WHERE enlace = ?",
                        vector(embeddings.embed((String) n.get("titulo"))), n.get("enlace"));
            }
            if (!pendientes.isEmpty()) {
                log.info("Embeddings calculados para {} noticias.", pendientes.size());
            }
        } catch (Exception e) {
            log.warn("No se pudieron calcular embeddings (¿BD u Ollama fuera de línea?): {}", e.getMessage());
        }
    }

    /** Búsqueda semántica con pgvector: los 5 titulares más cercanos a la pregunta. */
    private String contextoNoticias(String pregunta) {
        try {
            var rows = jdbc.queryForList(
                    "SELECT fecha, titulo, fuente FROM noticias WHERE embedding IS NOT NULL "
                            + "ORDER BY embedding <=> ?::vector LIMIT 5",
                    vector(embeddings.embed(pregunta)));
            if (rows.isEmpty()) {
                return "";
            }
            var sb = new StringBuilder("\nTitulares de noticias recientes relacionados con la pregunta:\n");
            rows.forEach(r -> sb.append("  [%s] %s (%s)%n"
                    .formatted(r.get("fecha"), r.get("titulo"), r.get("fuente"))));
            return sb.toString();
        } catch (Exception e) {
            log.warn("Sin contexto de noticias: {}", e.getMessage());
            return "";
        }
    }

    private static String vector(float[] v) {
        var sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }

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
