# ANDREAO Fleet — servidor remoto v3.5

Versão experimental isolada, baseada no Android v3.4 já compilado. Código do servidor (Node.js), navegador e testes integrados estão empacotados em `server-sources.tar.xz.b64`. O GitHub Actions valida o SHA-256 do pacote antes de executar os testes e compilar o app Android existente.

**Problema corrigido:** não apagar MP4s antigos em caso de upload inválido ou interrompido. A liberação da quota ocorre somente após a validação do SHA-256 do novo MP4. Remoção de temporários obsoletos após falhas. Testes reproduzem um MP4 real remotamente e provam que o carro pode ficar desconectado após sincronizar.

**Portal remoto:** filtros por veículo, data e ocorrências protegidas; vídeos carregados somente quando o gestor toca “Assistir”, permitindo reprodução por trechos HTTPS via HTTP Range. Arquivos já sincronizados podem ser assistidos sem baixar manualmente o MP4 completo.

**Limites reais:** não há servidor hospedado nem conexão móvel autorizada. O Android só envia MP4s finalizados mediante autorização em Wi-Fi não medido. A versão ainda exige infraestrutura persistente e HTTPS externo para acesso remoto; a bateria BYD só mostra SOC/SOH/12V se o firmware permitir leituras, nunca valores simulados. A gravação durante condução ou com veículo desligado permanece bloqueada por requisitos de segurança do OEM. 24h de vídeo original a 1,5 Mbps consomem aproximadamente 15 GiB, portanto a cota local padrão de servidor de 2 GiB não equivale a um dia de arquivos. Câmera de ré e privacidade dos ocupantes são prioritárias.

**Sem deploy, contrato, banco ou custos:** qualquer publicação na Internet exige aprovação específica do responsável. Branch separada da produção, sem merge.

Fonte completa está dentro do arquivo compactado; para conferir localmente: `base64 --decode server-sources.tar.xz.b64 | tar -xJ`. Diretório extraído: `remote-v35`.
