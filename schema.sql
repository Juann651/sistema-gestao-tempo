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
