package br.sgt;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Conquista do catálogo global (o administrador cria e edita; o progresso de cada usuário é individual). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Conquista(String id, String nome, String descricao, String icone, String tipo, int requisito, boolean ativa) {
    public String regra() {
        TipoConquista t = TipoConquista.de(tipo);
        return requisito + " " + (t == null ? "" : t.getUnidade());
    }
    public String tipoRotulo() {
        TipoConquista t = TipoConquista.de(tipo);
        return t == null ? tipo : t.getRotulo();
    }
}
