package br.sgt;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * duracao = tempo previsto/reservado (minutos). tempoGasto = esforço real (segundos), informado ao concluir.
 * iniciadaEm marca o começo do "modo foco": enquanto a reserva de tempo corre, só essa tarefa pode ser concluída.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Tarefa(String id, String nome, String descricao, int prioridade, int urgencia,
                     int complexidade, int duracao, String periodo, boolean concluida,
                     @JsonProperty("tempo_gasto") int tempoGasto,
                     @JsonProperty("iniciada_em") String iniciadaEm,
                     @JsonProperty("concluida_em") String concluidaEm,
                     @JsonProperty("concluida_por") String concluidaPor,
                     String email) {
    public int score() { return prioridade * 2 + urgencia * 3; }
    public String scoreCss() { int s = score(); return s >= 13 ? "n3" : s >= 9 ? "n2" : "n1"; }
    public Periodo turno() { return Periodo.de(periodo); }
    public Nivel prio() { return Nivel.de(prioridade); }
    public Nivel urg() { return Nivel.de(urgencia); }
    public Nivel comp() { return Nivel.de(complexidade); }

    // ---------- tempo ----------
    public int duracaoSeg() { return duracao * 60; }
    public String duracaoFmt() { return Tempo.legivel(duracaoSeg()); }
    public String gastoFmt() { return Tempo.legivel(tempoGasto); }
    /** Quem fez a tarefa: quem concluiu; se não houver, o dono. */
    public String quemFez() { return concluidaPor != null && !concluidaPor.isBlank() ? concluidaPor : email != null ? email : ""; }

    // ---------- em andamento (modo foco) ----------
    public long inicioMs() {
        if (iniciadaEm == null || iniciadaEm.isBlank()) return 0;
        try { return OffsetDateTime.parse(iniciadaEm).toInstant().toEpochMilli(); }
        catch (Exception e) {
            try { return Instant.parse(iniciadaEm).toEpochMilli(); } catch (Exception e2) { return 0; }
        }
    }
    public boolean emAndamento() { return !concluida && inicioMs() > 0; }
    public long fimMs() { return inicioMs() + duracaoSeg() * 1000L; }
    /** True enquanto o tempo reservado ainda não acabou: é o que trava as demais ações. */
    public boolean reservaAtiva(long agoraMs) { return emAndamento() && agoraMs < fimMs(); }
    public long restanteSeg(long agoraMs) { return Math.max(0, (fimMs() - agoraMs + 999) / 1000); }
    public long decorridoSeg(long agoraMs) { return emAndamento() ? Math.max(1, (agoraMs - inicioMs()) / 1000) : 0; }
    /** Sugestão para o pop-up de tempo gasto: cronômetro se estiver rodando, senão o que já foi registrado. */
    public long sugestaoGastoSeg(long agoraMs) { return emAndamento() ? decorridoSeg(agoraMs) : tempoGasto; }
    public String estado() { return concluida ? "feito" : emAndamento() ? "rodando" : "parado"; }
    /** Valor mostrado no contador quando ele não está correndo. */
    public int contadorSeg() { return concluida ? tempoGasto : duracaoSeg(); }
}
