-- Rode no SQL Editor do Supabase. Se as tabelas ja existem, rode so o bloco "E-MAIL DO DONO".
-- E-MAIL DO DONO (novo): guarda quem criou cada tarefa
alter table public.tarefas add column if not exists email text default (auth.jwt() ->> 'email');
update public.tarefas t set email = u.email from auth.users u where u.id = t.user_id and t.email is null;
