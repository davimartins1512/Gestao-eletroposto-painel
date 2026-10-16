ANDREÃO ELETROPOSTO — PAINEL ADMINISTRATIVO

O painel usa autenticação e banco de dados Supabase, o backend Railway para operações OCPP e pagamentos, e publicação automática pelo GitHub Pages.

Interface administrativa
- painel.html preserva os fluxos e os cálculos da operação.
- admin.css organiza a identidade visual e as adaptações para celular.
- admin-ui.js acrescenta ícones, títulos e uma visão das estações ativas; não envia comandos nem altera valores financeiros.
- admin-icon.svg contém o ícone da interface.
- fonts/InterVariable.woff2 usa a fonte Inter do projeto rsms/inter, com os caracteres latinos e sinais usados pela interface. A licença SIL OFL está em fonts/LICENSE.txt.
- sw.js mantém o cache apenas dos arquivos públicos do site, excluindo APIs autenticadas e respostas privadas.

Configuração
Preencha config.js com o endereço e a chave pública do projeto Supabase. Nunca use uma chave secreta no navegador. As credenciais privadas e as integrações de pagamento ficam no backend Railway.

Publicação
As alterações revisadas e mescladas na branch main são publicadas pelo GitHub Pages. O domínio de produção é andreaoeletroposto.com.br.

Carregadores sem uso
maintenance/archive-unused-chargers.sql registra a desativação dos cadastros iniciais CP002, CP003, CP004 e CP005. A consulta protege estações que já tiveram sessões, comunicação ou autorizações. As listas operacionais mostram somente cadastros ativos; o histórico permanece no banco.

Validação
Execute node --test tests/*.test.mjs e confira a interface no computador e no celular antes de publicar.

Backup
A exportação mantém a estrutura data do backup anterior. A importação disponível no painel importa motoristas e clientes; outras relações exigem um importador específico.
