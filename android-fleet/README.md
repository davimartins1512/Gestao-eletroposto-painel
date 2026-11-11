# ANDREAO Fleet — Diagnóstico completo v0.4 (Dolphin Mini)

Aplicativo experimental **somente leitura, offline**, para testar em uma única instalação o que a central Android do Dolphin Mini realmente expõe. O app inicia sem acessar câmeras, GPS ou microfone. Os testes são individuais e opt-in.

## Testes incluídos

1. Identificação do Android e firmware, sem IMEI/ICCID.
2. Bateria publicada pelo Android — **não confundir com SOC/SOH da bateria de tração BYD**.
3. Sensores Android disponíveis: acelerômetro, giroscópio, campo magnético, pressão e nomes dos sensores.
4. Câmeras Android Camera2: quantidade, IDs e direção, com permissão solicitada sob demanda.
5. GPS: provedores, idade/precisão da última posição em cache (sem salvar coordenadas no relatório).
6. Entradas de áudio disponíveis, sem capturar gravação; áudio/vídeo opcional no módulo legado de testes.
7. Tipo de rede, conectividade Android e espaço livre da área do aplicativo — sem teste de servidor.
8. Declaração explícita de ausência de acesso autenticado ao BMS, SOH, pressão de pneus, câmeras 360° OEM e comandos veiculares.
9. Abrir teste de vídeo manual, apenas com carro estacionado em P e informado; pode não funcionar no DiLink.
10. Relatório copiável ou exportável em TXT, além do último stack trace Java conhecido de uma sessão com erro.

## Segurança

- Nenhuma permissão INTERNET; o app não faz upload, telemetria contínua ou controle remoto.
- Nenhuma tentativa de acesso a CAN, OBD, root, ADB, sistemas BYD privados, funções de travamento ou atualização de firmware.
- A câmera de ré/estacionamento original deve continuar prioritária; abortar os testes se houver interferência.
- Não executar testes durante a condução.
- Microfone desligado por padrão, só no teste opcional com consentimento dos ocupantes.
- Este protótipo NÃO é um sistema de videomonitoramento operacional: sem armazenamento criptografado, streaming ao vivo, gravação em loop ou gravação automática de acidentes.
- Se falhar na abertura, consultar erro registrado na próxima inicialização. Erro nativo pode exigir logcat.

## Build e instalação

Java 17, Android Gradle Plugin 8.7.3, Gradle 8.9, SDK 35. Build: `gradle -p android-fleet :app:assembleDebug`.

O GitHub Actions da branch publica o artefato `Andreao-Fleet-Diagnostico-v0.4`; verificar status verde antes de usar o APK. Como a assinatura debug é gerada no runner, versões com assinaturas diferentes podem exigir desinstalação do APK anterior. Isso apaga os arquivos privados do app; exporte antes qualquer teste que deseje conservar.

### Uso no piloto

1. Com o carro em **P**, freio de estacionamento, sem deslocamento, abrir o app.
2. Tocar em 1 a 8 e exportar o relatório TXT. **Não inclui coordenadas exatas ou IMEI/ICCID.**
3. Só depois, e opcionalmente, abrir o teste 9 (câmera). Pode exibir zero câmeras apesar do veículo ter câmeras 360° BYD.
4. Reportar erros de inicialização e dados das APIs Android; não interpretar ausência de SOH/BMS como defeito do veículo.

## Correção v0.4

A exportação TXT usa MediaStore para gravar diretamente em Download/Downloads no Android 10+, sem depender do seletor de arquivos inexistente no DiLink. Em sistemas mais antigos ou quando o provedor Downloads não funcionar, tenta pasta privada Documents do aplicativo. Uma caixa informa o caminho exato ou o erro. Há também botão de resumo curto na tela. Nenhum dado é enviado à rede.
