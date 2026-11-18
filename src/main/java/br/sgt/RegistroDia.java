package br.sgt;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Resultado de um dia já encerrado (fica guardado e não muda mais). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RegistroDia(String data, int total, int concluidas, int taxa, String resultado,
                          @JsonProperty("turnos_completos") String turnosCompletos) {
    public String rotulo() {
        return switch (resultado == null ? "" : resultado) {
            case "ganhou" -> "Ganhou o dia";
            case "perdeu" -> "Sequência perdida";
            case "protegido" -> "Salvo pelo protetor";
            default -> "Dia neutro";
        };
    }
}
