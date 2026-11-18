# ANDREAO Fleet v3.6 — isolamento e segurança
Pacote incremental sobre a branch v3.5, sem produção nem instalação automática.

- Correção de risco de perda de vídeos antigos durante falha no commit de upload.
- Proteção de ocorrências por metadados escritos atomicamente.
- Controle individual de reserva para uploads concorrentes.
- Rotas de biblioteca sem ambiguidade entre IDs contendo '__'.
- Fila Android de Wi-Fi autorizado: ocorrências protegidas primeiro, depois gravações antigas, sem que arquivo excessivo bloqueie tudo.
- Novos testes de falha de disco e colisão de IDs; build Android tentado via CI. Nenhum teste foi feito no carro.

Reconstrução do backend: extrair bundle v3.5, extrair source-patch v3.6, aplicar os três scripts e copiar testes. O fluxo automatizado faz essa reconstrução e valida hashes.

Ainda faltam hospedagem HTTPS com armazenamento persistente, autorização de acesso BMS na multimídia e segurança física da câmera durante condução. Não substituir Electro.

Corrigida também uma condição rara da limpeza periódica: ela não descarta mídia antiga apenas para abrir espaço a upload ainda não validado. Incluído teste HTTP com transmissão parcial em andamento (12 testes totais).
