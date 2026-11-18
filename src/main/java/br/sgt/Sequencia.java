package br.sgt;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/** Progresso de consistência de um usuário. A sequência atual e o recorde só mudam quando um dia é fechado. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Sequencia(@JsonProperty("user_id") String userId, int atual, int recorde, int protetores,
                        @JsonProperty("ultimo_fechado") String ultimoFechado,
                        @JsonProperty("dias_alta") int diasAlta, @JsonProperty("dias_turno") int diasTurno) {
    public static Sequencia inicial(String userId, LocalDate ultimoFechado) {
        return new Sequencia(userId, 0, 0, 0, ultimoFechado.toString(), 0, 0);
    }
    public LocalDate ultimoDia() {
        try { return LocalDate.parse(ultimoFechado); } catch (Exception e) { return Tempo.hoje().minusDays(1); }
    }
    public Map<String, Object> paraBanco() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("user_id", userId); m.put("atual", atual); m.put("recorde", recorde); m.put("protetores", protetores);
        m.put("ultimo_fechado", ultimoFechado); m.put("dias_alta", diasAlta); m.put("dias_turno", diasTurno);
        return m;
    }
}
