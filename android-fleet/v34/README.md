# ANDREAO Fleet 3.4 — envio automático em Wi-Fi autorizado

Incremento sobre Android v3.2 remoto e v3.3 telemetria somente leitura. O proprietário configura servidor HTTPS/chave do veículo, aceita as condições e autoriza a rede Wi-Fi atualmente conectada. JobScheduler tenta sincronizar até 2 vídeos MP4 finalizados por janela de cerca de 15 minutos, se o Android permitir. Limite de 110 MiB por tentativa. Desligar o recurso revoga a autorização e cancela o job. O app não abre câmera nem mantém o carro ligado ou a tela acordada.

Só sincroniza quando o Android declara transporte Wi-Fi validado, NÃO MEDIDO, sem transporte celular/VPN, tela interativa e SSID idêntico ao autorizado. A conexão de upload é vinculada à interface Wi-Fi pelo módulo HTTPS v3.2. Se o SSID for inacessível por permissões ou por firmware BYD, falha fechado. Wi-Fi não medido não garante inexistência de custos de provedor. Jobs não são garantidos em firmware DiLink; não são executados durante reboot. Abrir o app depois da reinicialização reprograma o agendamento.

Acesso remoto requer servidor HTTPS hospedado com autenticação e persistência; não há publicação nesta branch. BMS/SOH 12V devem ter fonte real autorizada; valores não verificados ficam indisponíveis. Gravação durante movimento não foi habilitada. Não substituir Electro com esta versão, não mexer em Supabase/produção.
