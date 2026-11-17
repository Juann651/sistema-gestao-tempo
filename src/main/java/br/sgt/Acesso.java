package br.sgt;

import br.sgt.SupabaseService.Sessao;
import java.util.function.Function;

/**
 * "Quem está pedindo": executa uma chamada ao Supabase em nome de um usuário.
 * No site vem da sessão do navegador; na Alexa vem do token da conta vinculada.
 */
public interface Acesso {
    <T> T chamar(Function<Sessao, T> f);
}
