-- Rode no SQL Editor do Supabase. Pode rodar de novo sem problema (tudo é "if not exists" / idempotente).

-- 1) E-MAIL DO DONO: guarda quem criou cada tarefa
alter table public.tarefas add column if not exists email text default (auth.jwt() ->> 'email');
update public.tarefas t set email = u.email from auth.users u where u.id = t.user_id and t.email is null;

-- 2) ESFORÇO POR TAREFA / USUÁRIO + MODO FOCO
alter table public.tarefas add column if not exists tempo_gasto integer not null default 0;  -- esforço real, em segundos
alter table public.tarefas add column if not exists iniciada_em timestamptz;                 -- quando entrou em andamento
alter table public.tarefas add column if not exists concluida_em timestamptz;
alter table public.tarefas add column if not exists concluida_por text;                      -- e-mail de quem concluiu

-- 3) CAPACIDADE COM HORAS/MIN/SEG: passa a ser guardada em segundos
alter table public.capacidades add column if not exists segundos integer;
update public.capacidades set segundos = minutos * 60 where segundos is null;

-- 4) TURNO MADRUGADA: troca a regra que só aceitava manha/tarde/noite (se existir)
do $$
declare r record;
begin
  for r in
    select conrelid::regclass as tabela, conname
    from pg_constraint
    where contype = 'c'
      and conrelid in ('public.tarefas'::regclass, 'public.capacidades'::regclass)
      and pg_get_constraintdef(oid) ilike '%periodo%'
  loop
    execute format('alter table %s drop constraint %I', r.tabela, r.conname);
  end loop;
end $$;
alter table public.tarefas      add constraint tarefas_periodo_check      check (periodo in ('madrugada','manha','tarde','noite'));
alter table public.capacidades  add constraint capacidades_periodo_check  check (periodo in ('madrugada','manha','tarde','noite'));

-- =====================================================================================================
-- 5) CONSISTÊNCIA 🔥, CONQUISTAS 🏆 E PAINEL ADMIN  (atualização de dezembro: pode rodar de novo sem problema)
--    Depois de rodar, defina SUPABASE_SERVICE_KEY no servidor (veja ATUALIZACAO.md).
-- =====================================================================================================

-- 5.1) Dia planejado de cada tarefa (padrão = hoje em Brasília) e replanejamento.
--      Tarefas que já existem passam a valer para HOJE: ninguém perde sequência por tarefas antigas.
alter table public.tarefas add column if not exists data date not null default ((now() at time zone 'America/Sao_Paulo')::date);
alter table public.tarefas add column if not exists replanejada_em timestamptz;
create index if not exists tarefas_user_data on public.tarefas (user_id, data);

-- 5.2) Perfis e papéis (USER / ADMIN). Toda conta nova nasce USER. Só o servidor (service_role) altera o papel.
create table if not exists public.perfis (
  id        uuid primary key references auth.users(id) on delete cascade,
  email     text,
  role      text not null default 'USER' check (role in ('USER','ADMIN')),
  criado_em timestamptz not null default now()
);
create or replace function public.novo_perfil() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  insert into public.perfis (id, email) values (new.id, new.email) on conflict (id) do nothing;
  return new;
end $$;
drop trigger if exists ao_criar_usuario on auth.users;
create trigger ao_criar_usuario after insert on auth.users for each row execute function public.novo_perfil();
insert into public.perfis (id, email) select id, email from auth.users on conflict (id) do nothing;

-- 5.3) Regras globais da consistência (editáveis no painel admin)
create table if not exists public.configuracoes (chave text primary key, valor text not null);
insert into public.configuracoes (chave, valor) values
  ('limiar_dia', '70'),        -- % de tarefas concluídas para ganhar o dia
  ('limiar_alto', '80'),       -- % usado nas conquistas de "alta conclusão"
  ('protetor_a_cada', '7'),    -- ganha um protetor a cada N dias seguidos (0 = desligado)
  ('max_protetores', '2')      -- máximo de protetores guardados
on conflict (chave) do nothing;

-- 5.4) Catálogo GLOBAL de conquistas (o progresso é individual)
create table if not exists public.conquistas (
  id        uuid primary key default gen_random_uuid(),
  nome      text not null,
  descricao text not null default '',
  icone     text not null default '🏆',
  tipo      text not null check (tipo in ('SEQUENCIA','TOTAL_TAREFAS','DIAS_ALTA_CONCLUSAO','DIAS_TURNO_COMPLETO')),
  requisito integer not null check (requisito > 0),
  ativa     boolean not null default true
);
insert into public.conquistas (nome, descricao, icone, tipo, requisito)
select * from (values
  ('Primeiros passos',        'Complete 3 dias seguidos',                          '🔥', 'SEQUENCIA', 3),
  ('Uma semana de foco',      'Complete 7 dias seguidos',                          '⚡', 'SEQUENCIA', 7),
  ('Criando hábitos',         'Complete 15 dias seguidos',                         '🌱', 'SEQUENCIA', 15),
  ('Disciplinado',            'Complete 30 dias seguidos',                         '🥉', 'SEQUENCIA', 30),
  ('Persistente',             'Complete 60 dias seguidos',                         '🥈', 'SEQUENCIA', 60),
  ('Imparável',               'Complete 100 dias seguidos',                        '🏆', 'SEQUENCIA', 100),
  ('Mestre da consistência',  'Complete 180 dias seguidos',                        '💎', 'SEQUENCIA', 180),
  ('Lenda da disciplina',     'Complete 365 dias seguidos',                        '👑', 'SEQUENCIA', 365),
  ('Além dos limites',        'Complete 500 dias seguidos',                        '🌟', 'SEQUENCIA', 500),
  ('Lenda absoluta',          'Complete 1000 dias seguidos',                       '🔥', 'SEQUENCIA', 1000),
  ('Cem tarefas',             'Conclua 100 tarefas',                               '✅', 'TOTAL_TAREFAS', 100),
  ('Quinhentas tarefas',      'Conclua 500 tarefas',                               '🏅', 'TOTAL_TAREFAS', 500),
  ('Rotina de alto nível',    'Tenha 30 dias com alta conclusão (padrão: 80%)',    '📈', 'DIAS_ALTA_CONCLUSAO', 30),
  ('Turno perfeito',          'Tenha 7 dias com um turno 100% concluído',          '🎯', 'DIAS_TURNO_COMPLETO', 7)
) as v(nome, descricao, icone, tipo, requisito)
where not exists (select 1 from public.conquistas);

-- 5.5) Progresso individual
create table if not exists public.sequencias (
  user_id        uuid primary key references auth.users(id) on delete cascade,
  atual          integer not null default 0,
  recorde        integer not null default 0,
  protetores     integer not null default 0,
  ultimo_fechado date,                         -- último dia já avaliado
  dias_alta      integer not null default 0,
  dias_turno     integer not null default 0
);
create table if not exists public.registros_dia (
  user_id          uuid not null references auth.users(id) on delete cascade,
  data             date not null,
  total            integer not null,
  concluidas       integer not null,
  taxa             integer not null,
  resultado        text not null check (resultado in ('ganhou','perdeu','neutro','protegido')),
  turnos_completos text not null default '',
  primary key (user_id, data)
);
create table if not exists public.conquistas_usuario (
  user_id         uuid not null references auth.users(id) on delete cascade,
  conquista_id    uuid not null references public.conquistas(id) on delete cascade,
  desbloqueada_em timestamptz not null default now(),
  primary key (user_id, conquista_id)
);
create table if not exists public.dias_descanso (
  user_id uuid not null references auth.users(id) on delete cascade,
  data    date not null,
  primary key (user_id, data)
);

-- 5.6) SEGURANÇA (RLS): cada pessoa só LÊ os próprios dados; ninguém escreve direto.
--      Toda gravação de consistência, conquistas, papéis e regras é feita pelo servidor com a service_role.
alter table public.perfis             enable row level security;
alter table public.configuracoes      enable row level security;
alter table public.conquistas         enable row level security;
alter table public.sequencias         enable row level security;
alter table public.registros_dia      enable row level security;
alter table public.conquistas_usuario enable row level security;
alter table public.dias_descanso      enable row level security;

drop policy if exists perfis_proprio on public.perfis;
create policy perfis_proprio on public.perfis for select to authenticated using (auth.uid() = id);
drop policy if exists conquistas_leitura on public.conquistas;
create policy conquistas_leitura on public.conquistas for select to authenticated using (true);
drop policy if exists sequencias_proprias on public.sequencias;
create policy sequencias_proprias on public.sequencias for select to authenticated using (auth.uid() = user_id);
drop policy if exists registros_proprios on public.registros_dia;
create policy registros_proprios on public.registros_dia for select to authenticated using (auth.uid() = user_id);
drop policy if exists desbloqueios_proprios on public.conquistas_usuario;
create policy desbloqueios_proprios on public.conquistas_usuario for select to authenticated using (auth.uid() = user_id);
drop policy if exists descanso_proprio on public.dias_descanso;
create policy descanso_proprio on public.dias_descanso for select to authenticated using (auth.uid() = user_id);
-- (configuracoes não tem política: só a service_role enxerga)

-- 5.7) PRIMEIRO ADMINISTRADOR (e-mail já preenchido). Depois, use o painel /admin para promover outros.
update public.perfis set role = 'ADMIN' where email = 'juansantosleal651@gmail.com';
