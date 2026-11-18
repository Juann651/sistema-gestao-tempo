package br.sgt;

import br.sgt.MotorConsistencia.Config;
import br.sgt.MotorConsistencia.Dia;
import br.sgt.MotorConsistencia.Estado;
import br.sgt.MotorConsistencia.Metricas;
import br.sgt.MotorConsistencia.Passo;
import br.sgt.MotorConsistencia.Progresso;
import br.sgt.MotorConsistencia.TarefaDia;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Consistência 🔥, conquistas 🏆 e estatísticas. As regras em si ficam em {@link MotorConsistencia}.
 *
 * O dia é fechado de forma "preguiçosa": na primeira vez que o usuário (ou a Alexa) abre o sistema depois da
 * meia-noite de Brasília, todos os dias que passaram são avaliados, em ordem, e gravados. Um dia fechado nunca
 * é reavaliado, então mexer em tarefas antigas não recupera uma sequência perdida. Para cada dia só contam as
 * tarefas planejadas para ele e concluídas ATÉ a meia-noite daquele dia.
 *
 * Tudo aqui é gravado com a chave de serviço (só o servidor tem). Se ela não estiver configurada, ou se as
 * tabelas ainda não existirem, o recurso fica desligado e o restante do SGT continua funcionando.
 */
@Service
public class ConsistenciaService {
    private static final Logger LOG = LoggerFactory.getLogger(ConsistenciaService.class);
    private static final long ATRASO_MS = 30_000;

    public record Atualizacao(Sequencia seq, List<Conquista> novas) { }
    private record Cache(Sequencia seq, LocalDate dia, long em) { }
    public record Celula(String rotulo, String estado, String icone, String legenda) { }
    public record Proxima(Conquista conquista, int faltam, int percentual) { }
    public record Descanso(String iso, String fmt) { }
    public record Painel(Sequencia seq, Config cfg, Progresso hoje, boolean hojeDescanso, List<Celula> semana,
                         Proxima proxima, List<Descanso> descansos, List<Conquista> novas) { }
    public record Estatisticas(int diasGanhos, int diasPerdidos, int diasProtegidos, int diasNeutros, int taxaMedia,
                               long tarefasConcluidas, String esforco, String turnoTop) { }
    public record ConquistaView(Conquista conquista, boolean desbloqueada, String quando, int atual, int percentual) { }
    public record PaginaConquistas(List<ConquistaView> itens, long desbloqueadas, List<Conquista> novas, Sequencia seq) { }
    public record DiaHistorico(RegistroDia registro, String data, String semana, List<Tarefa> tarefas) { }

    private final SupabaseService sb;
    private final Map<String, Cache> cache = new ConcurrentHashMap<>();
    private final Map<String, Object> travas = new ConcurrentHashMap<>();
    private volatile Config cfg;
    private volatile long cfgEm;

    public ConsistenciaService(SupabaseService sb) { this.sb = sb; }

    public boolean ativo() { return sb.temServico(); }

    // ---------- regras configuráveis ----------
    public Config config() {
        Config atual = cfg;
        if (atual != null && System.currentTimeMillis() - cfgEm < 60_000) return atual;
        Config pad = Config.padrao();
        try {
            Map<String, String> m = sb.configuracoes();
            Config nova = new Config(inteiro(m, "limiar_dia", pad.limiarDia(), 1, 100),
                    inteiro(m, "limiar_alto", pad.limiarAlto(), 1, 100),
                    inteiro(m, "protetor_a_cada", pad.protetorACada(), 0, 365),
                    inteiro(m, "max_protetores", pad.maxProtetores(), 0, 10));
            cfg = nova; cfgEm = System.currentTimeMillis();
            return nova;
        } catch (RuntimeException e) {
            return atual != null ? atual : pad;
        }
    }

    public void invalidarConfig() { cfg = null; cfgEm = 0; }

    private static int inteiro(Map<String, String> m, String chave, int padrao, int min, int max) {
        try { return Math.max(min, Math.min(max, Integer.parseInt(m.getOrDefault(chave, "").trim()))); }
        catch (NumberFormatException e) { return padrao; }
    }

    // ---------- fechamento dos dias ----------
    static List<TarefaDia> tarefasDoDia(List<Tarefa> ts, LocalDate dia) {
        Instant fim = dia.plusDays(1).atStartOfDay(Tempo.ZONA).toInstant();
        List<TarefaDia> l = new ArrayList<>();
        for (Tarefa t : ts) if (t.dia().equals(dia)) l.add(new TarefaDia(t.feitaAte(fim), t.turno()));
        return l;
    }

    /**
     * Fecha os dias que passaram, grava o resultado, atualiza a sequência e desbloqueia conquistas.
     * forcar = ignora o intervalo de 30 s entre verificações. Nunca lança erro: em caso de falha devolve seq = null.
     */
    public Atualizacao atualizar(String userId, List<Tarefa> ts, boolean forcar) {
        if (!ativo()) return new Atualizacao(null, List.of());
        LocalDate hoje = Tempo.hoje();
        Cache c = cache.get(userId);
        if (!forcar && c != null && c.dia().equals(hoje) && System.currentTimeMillis() - c.em() < ATRASO_MS)
            return new Atualizacao(c.seq(), List.of());
        try {
            synchronized (travas.computeIfAbsent(userId, k -> new Object())) {
                Config conf = config();
                Sequencia seq = sb.sequencia(userId);
                boolean mudou = seq == null;
                if (seq == null) seq = Sequencia.inicial(userId, hoje.minusDays(1));   // começa a valer a partir de hoje
                if (seq.ultimoDia().isBefore(hoje.minusDays(1))) {
                    LocalDate de = seq.ultimoDia().plusDays(1);
                    if (de.isBefore(hoje.minusDays(366))) de = hoje.minusDays(366);
                    Set<LocalDate> descanso = sb.diasDescanso(userId);
                    Estado e = new Estado(seq.atual(), seq.recorde(), seq.protetores(), seq.diasAlta(), seq.diasTurno());
                    List<Map<String, Object>> registros = new ArrayList<>();
                    for (LocalDate d = de; d.isBefore(hoje); d = d.plusDays(1)) {
                        Dia dia = MotorConsistencia.avaliar(d, tarefasDoDia(ts, d), descanso.contains(d), conf);
                        Passo p = MotorConsistencia.aplicar(e, dia, conf);
                        e = p.estado();
                        registros.add(registro(userId, dia, p));
                    }
                    sb.salvarRegistros(registros);
                    seq = new Sequencia(userId, e.atual(), e.recorde(), e.protetores(), hoje.minusDays(1).toString(), e.diasAlta(), e.diasTurno());
                    mudou = true;
                }
                if (mudou) sb.salvarSequencia(seq);
                List<Conquista> novas = avaliarConquistas(userId, seq, ts);
                cache.put(userId, new Cache(seq, hoje, System.currentTimeMillis()));
                return new Atualizacao(seq, novas);
            }
        } catch (RuntimeException e) {
            LOG.warn("Consistência indisponível: {}", e.getMessage());
            return new Atualizacao(c == null ? null : c.seq(), List.of());
        }
    }

    private static Map<String, Object> registro(String userId, Dia dia, Passo p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("user_id", userId);
        m.put("data", dia.data().toString());
        m.put("total", dia.total());
        m.put("concluidas", dia.concluidas());
        m.put("taxa", dia.taxa());
        m.put("resultado", p.resultado().codigo());
        StringBuilder t = new StringBuilder();
        for (Periodo pe : Periodo.values()) if (dia.turnosCompletos().contains(pe)) { if (t.length() > 0) t.append(','); t.append(pe.getCodigo()); }
        m.put("turnos_completos", t.toString());
        return m;
    }

    private static Metricas metricas(Sequencia seq, List<Tarefa> ts) {
        return new Metricas(seq.recorde(), (int) ts.stream().filter(Tarefa::concluida).count(), seq.diasAlta(), seq.diasTurno());
    }

    /** Desbloqueios são permanentes: desativar ou editar uma conquista depois não os remove. */
    private List<Conquista> avaliarConquistas(String userId, Sequencia seq, List<Tarefa> ts) {
        Set<String> ja = sb.conquistasDoUsuario(userId).keySet();
        Metricas m = metricas(seq, ts);
        List<Conquista> novas = new ArrayList<>();
        for (Conquista c : sb.conquistas()) {
            if (!c.ativa() || ja.contains(c.id())) continue;
            if (MotorConsistencia.atingiu(c.tipo(), c.requisito(), m)) { sb.desbloquear(userId, c.id()); novas.add(c); }
        }
        return novas;
    }

    // ---------- dia de descanso ----------
    /** Descanso é planejado: só de amanhã em diante (até 60 dias). Evita "descansar" no último minuto para não perder a sequência. */
    private static void validarDescanso(LocalDate d) {
        LocalDate hoje = Tempo.hoje();
        if (!d.isAfter(hoje)) throw new ErroSupabase(400, "O dia de descanso precisa ser planejado: escolha de amanhã em diante.");
        if (d.isAfter(hoje.plusDays(60))) throw new ErroSupabase(400, "Escolha uma data dentro dos próximos 60 dias.");
    }

    public void marcarDescanso(String userId, LocalDate d) { validarDescanso(d); sb.marcarDescanso(userId, d); }
    public void removerDescanso(String userId, LocalDate d) { validarDescanso(d); sb.removerDescanso(userId, d); }

    // ---------- painel da consistência ----------
    public Painel painel(String userId, List<Tarefa> ts) {
        Atualizacao a = atualizar(userId, ts, true);
        if (a.seq() == null) return null;
        Sequencia seq = a.seq();
        Config conf = config();
        LocalDate hoje = Tempo.hoje();
        Set<LocalDate> descansos = sb.diasDescanso(userId);
        Progresso prog = MotorConsistencia.progresso(tarefasDoDia(ts, hoje), conf);
        boolean hojeDescanso = descansos.contains(hoje);

        Map<LocalDate, RegistroDia> porData = new LinkedHashMap<>();
        for (RegistroDia r : sb.registros(userId, hoje.minusDays(14))) porData.put(LocalDate.parse(r.data()), r);

        String[] nomes = {"SEG", "TER", "QUA", "QUI", "SEX", "SÁB", "DOM"};
        LocalDate seg = hoje.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        List<Celula> semana = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            LocalDate d = seg.plusDays(i);
            String n = nomes[i];
            if (d.equals(hoje)) {
                if (hojeDescanso) semana.add(new Celula(n, "descanso", "😴", "Descanso"));
                else if (prog.total() > 0 && prog.meta()) semana.add(new Celula(n, "ganhando", "🔥", "Meta batida"));
                else semana.add(new Celula(n, "hoje", "⏳", prog.total() == 0 ? "Hoje" : prog.feitas() + "/" + prog.total()));
            } else if (d.isAfter(hoje)) {
                if (descansos.contains(d)) semana.add(new Celula(n, "descanso", "😴", "Descanso"));
                else semana.add(new Celula(n, "futuro", "🔒", "-"));
            } else {
                RegistroDia r = porData.get(d);
                if (r == null) semana.add(new Celula(n, "vazio", "·", "-"));
                else semana.add(switch (r.resultado()) {
                    case "ganhou" -> new Celula(n, "ganhou", "🔥", "Feito");
                    case "perdeu" -> new Celula(n, "perdeu", "✖", "Perdeu");
                    case "protegido" -> new Celula(n, "protegido", "🛡️", "Protegido");
                    default -> descansos.contains(d) ? new Celula(n, "descanso", "😴", "Descanso") : new Celula(n, "neutro", "•", "Neutro");
                });
            }
        }

        Conquista prox = sb.conquistas().stream()
                .filter(c -> c.ativa() && "SEQUENCIA".equals(c.tipo()) && c.requisito() > seq.atual())
                .min(Comparator.comparingInt(Conquista::requisito)).orElse(null);
        Proxima proxima = prox == null ? null
                : new Proxima(prox, prox.requisito() - seq.atual(), seq.atual() * 100 / prox.requisito());

        List<Descanso> futuros = descansos.stream().filter(d -> d.isAfter(hoje)).sorted()
                .map(d -> new Descanso(d.toString(), Tempo.diaSemana(d) + ", " + Tempo.data(d))).toList();
        return new Painel(seq, conf, prog, hojeDescanso, semana, proxima, futuros, a.novas());
    }

    // ---------- estatísticas ----------
    public Estatisticas estatisticas(String userId, List<Tarefa> ts) {
        List<RegistroDia> regs = sb.registros(userId, null);
        int g = 0, p = 0, pr = 0, n = 0, soma = 0, comTarefas = 0;
        for (RegistroDia r : regs) {
            switch (r.resultado()) {
                case "ganhou" -> g++;
                case "perdeu" -> p++;
                case "protegido" -> pr++;
                default -> n++;
            }
            if (r.total() > 0) { soma += r.taxa(); comTarefas++; }
        }
        List<Tarefa> feitas = ts.stream().filter(Tarefa::concluida).toList();
        Map<Periodo, Integer> porTurno = new EnumMap<>(Periodo.class);
        for (Tarefa t : feitas) porTurno.merge(t.turno(), 1, Integer::sum);
        String top = porTurno.entrySet().stream().max(Map.Entry.comparingByValue()).map(e -> e.getKey().getRotulo()).orElse("—");
        return new Estatisticas(g, p, pr, n, comTarefas == 0 ? 0 : soma / comTarefas, feitas.size(),
                Tempo.legivel(feitas.stream().mapToLong(Tarefa::tempoGasto).sum()), top);
    }

    // ---------- conquistas do usuário ----------
    public PaginaConquistas conquistasDoUsuario(String userId, List<Tarefa> ts) {
        Atualizacao a = atualizar(userId, ts, true);
        if (a.seq() == null) return null;
        Map<String, String> ja = sb.conquistasDoUsuario(userId);
        Metricas m = metricas(a.seq(), ts);
        List<ConquistaView> itens = new ArrayList<>();
        for (Conquista c : sb.conquistas()) {
            boolean tem = ja.containsKey(c.id());
            if (!c.ativa() && !tem) continue;   // desativada: só aparece para quem já desbloqueou
            int atual = MotorConsistencia.valorAtual(c.tipo(), m);
            int pct = c.requisito() <= 0 ? 0 : Math.min(100, atual * 100 / c.requisito());
            itens.add(new ConquistaView(c, tem, tem ? dataCurta(ja.get(c.id())) : "", Math.min(atual, c.requisito()), tem ? 100 : pct));
        }
        return new PaginaConquistas(itens, itens.stream().filter(ConquistaView::desbloqueada).count(), a.novas(), a.seq());
    }

    private static String dataCurta(String iso) {
        try { return OffsetDateTime.parse(iso).atZoneSameInstant(Tempo.ZONA).format(DateTimeFormatter.ofPattern("dd/MM/yyyy")); }
        catch (Exception e) { return iso == null ? "" : iso; }
    }

    // ---------- histórico ----------
    public List<DiaHistorico> historico(String userId, List<Tarefa> ts) {
        List<DiaHistorico> l = new ArrayList<>();
        for (RegistroDia r : sb.registros(userId, Tempo.hoje().minusDays(60))) {
            LocalDate d = LocalDate.parse(r.data());
            List<Tarefa> doDia = ts.stream().filter(t -> t.dia().equals(d))
                    .sorted(Comparator.comparingInt(Tarefa::score).reversed()).toList();
            l.add(new DiaHistorico(r, Tempo.data(d), Tempo.diaSemana(d), doDia));
        }
        return l;
    }
}
