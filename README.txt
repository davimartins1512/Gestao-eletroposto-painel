GESTÃO OPERACIONAL V5 — ONLINE COM SUPABASE

O que mudou:
- Banco online PostgreSQL via Supabase
- Login real com e-mail e senha
- Dados sincronizados entre celular e computador
- Row Level Security por usuário
- Motoristas, diárias, cargas, clientes, despesas e relatórios
- PWA instalável
- Importação básica de backup V4
- Netlify-ready

PASSO 1 — CRIAR SUPABASE
1. Crie um projeto no Supabase.
2. Abra SQL Editor.
3. Cole e execute todo o conteúdo de supabase/schema.sql.
4. Em Authentication > Users, crie seu usuário com e-mail e senha.

PASSO 2 — CONFIGURAR O SITE
1. Abra config.js.
2. Preencha:
   supabaseUrl
   supabaseKey
3. Use a Publishable Key do projeto. Não coloque Secret Key no navegador.

PASSO 3 — NETLIFY
1. Extraia este ZIP.
2. Arraste a pasta extraída no Netlify.
3. Abra o site.
4. Entre com o e-mail/senha criado no Supabase.

OBSERVAÇÃO SOBRE V4
A importação automática completa das relações da V4 para V5 exigiria remapear IDs entre tabelas.
Nesta versão o botão de importação traz motoristas e clientes. Diárias/cargas antigas podem ser migradas depois com um importador dedicado.
