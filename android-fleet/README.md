# ANDREAO Fleet — Diagnóstico de integração v0.9 (Dolphin Mini)

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

O GitHub Actions da branch publica o artefato `Andreao-Fleet-Diagnostico-v0.5`; verificar status verde antes de usar o APK. Como a assinatura debug é gerada no runner, versões com assinaturas diferentes podem exigir desinstalação do APK anterior. Isso apaga os arquivos privados do app; exporte antes qualquer teste que deseje conservar.

### Uso no piloto

1. Com o carro em **P**, freio de estacionamento, sem deslocamento, abrir o app.
2. Tocar em 1 a 8 e exportar o relatório TXT. **Não inclui coordenadas exatas ou IMEI/ICCID.**
3. Só depois, e opcionalmente, abrir o teste 9 (câmera). Pode exibir zero câmeras apesar do veículo ter câmeras 360° BYD.
4. Reportar erros de inicialização e dados das APIs Android; não interpretar ausência de SOH/BMS como defeito do veículo.

## Correção v0.5

A exportação TXT usa MediaStore para gravar diretamente em Download/Downloads no Android 10+, sem depender do seletor de arquivos inexistente no DiLink. Em sistemas mais antigos ou quando o provedor Downloads não funcionar, tenta pasta privada Documents do aplicativo. Uma caixa informa o caminho exato ou o erro. Há também botão de resumo curto na tela. Nenhum dado é enviado à rede.

## v0.5 — diagnóstico automático

O botão **EXECUTAR TODOS OS TESTES E SALVAR LOG** pede as permissões Android de câmera e GPS (se necessário), continua caso sejam negadas e executa, em sequência, todos os oito diagnósticos somente-leitura. Cada seção é acumulada em um único relatório, que é gravado automaticamente em **Download** pelo MediaStore (Android 10+) ou na pasta privada de documentos como alternativa. O teste 9 de gravação é deliberadamente manual e NÃO faz parte do lote: gravação de vídeo e microfone não é apropriada sem confirmação de carro parado e consentimento. O log não inclui coordenadas nem dados pessoais do veículo.


## v0.6 — GPS e movimento local, exportação consolidada

- Teste de **GPS e movimento real**, iniciado manualmente, limitado a **180 segundos**; usa serviço Android em primeiro plano com notificação de parar, sem execução automática.
- Localização (coordenadas, acurácia, velocidade GPS quando houver) e sensores Android são amostrados em CSV local. Não é telemetria de tração nem odômetro BYD. Se a posição GPS não atualizar, o log registra somente sensores e evento de indisponibilidade. O teste não promete funcionar com a tela desligada no DiLink: testar em veículo parado.
- Botão **EXPORTAR LOG ÚNICO** concatena o diagnóstico anterior, CSV de GPS com coordenadas exatas e resultado do teste de microfone. Requer interromper o rastreador primeiro. O TXT final pode conter **endereços/rotas privados**; revise antes de compartilhar.
- Teste real do microfone: 5 s, permissão e consentimento dos ocupantes, não grava de forma permanente. Pico de áudio não garante inteligibilidade.
- App continua SEM permissão INTERNET; nenhum dado sai da multimídia.
- Não mexe no CAN, BMS, firmware ou câmera de ré. Não usar a tela durante a direção.

## v0.7 — ensaio de fonte OEM somente-leitura e contadores Android

- **Botão 15** consulta opcionalmente `content://com.byd.carStatusProvider/car_status` para verificar acesso do APK a campos conhecidos de manutenção e contagem de incidentes. O provedor foi relatado pela comunidade em outro firmware; **não há acesso BYD validado no Dolphin Mini**. Retorna apenas valores de campos numéricos permitidos e contagem de elementos de série elétrica sem copiar dados brutos nem identificadores. Teste isolado em thread, sem escrita.
- **Botão 16** consulta `TrafficStats` para contadores de bytes acumulados do Android e do próprio app. Não fornece franquia/saldo do SIM e nunca deve ser rotulado como saldo da operadora.
- NÃO consulta CAN, nem configurações de veículo, e não usa shell, root ou bypass de permissões OEM. Compatibilidade dependente de software/características de cada BYD.
- A câmera BYD original continua **não acessível pela API Camera2 no veículo testado**. Pesquisas externas sugerem serviço de sistema restrito; acesso a gravação contínua exige interface autorizada ou DVR dedicado. Nenhum modo de áudio oculto foi adicionado; áudio de teste exige aviso e autorização dos ocupantes.
- **Relatório completo 1–8 não executa automaticamente o botão 15**, pois é uma consulta opcional ao provedor OEM. Se testar, use a exportação manual após o resultado aparecer.

## v0.8 — teste de armazenamento e perfis ajustáveis

O botão 17 CONFIGURAR ARMAZENAMENTO / USB / LIMITES permite modificar na multimídia:
- mídia interna, removível ou automática; troca de mídia e retorno automático à interna como preferências;
- limite de uso do disco (vazio = sem teto lógico), espaço mínimo livre, retenção circular e duração de segmentos;
- resolução desejada, FPS, bitrate, qualidade de live, orçamento de uso de internet e alerta;
- tensão mínima desejada para sentinela quando um sensor real de 12V existir.

O botão VERIFICAR descobre pastas próprias do aplicativo em getFilesDir() e getExternalFilesDirs(), identifica removível via Environment.isExternalStorageRemovable, cria teste de escrita temporário pequeno e remove o teste. NÃO acessa conteúdo do Electro ou qualquer outro aplicativo. Um USB visível no gerenciador original pode NÃO aparecer gravável pelo Android; então verifique. Não há gravação de vídeos, microfone escondido ou transmissão nesta versão. Preferências locais não são prova de funcionamento. O futuro gravador deverá reavaliar os volumes antes de cada segmento, nunca preencher o disco e preservar incidentes de sobrescrita.

O Android 10 pode permitir apenas pastas específicas por aplicativo; um seletor do Storage Access Framework (SAF) pode ser necessário para escolher outra pasta e depende do provedor da BYD. Não usar permissões de acesso irrestrito.

## v0.9 — foco na integração das câmeras originais e áudio

A câmera padrão Camera2 retornou zero IDs no Dolphin Mini. O novo botão
**18 · VERIFICAR INTERFACE DAS CÂMERAS ORIGINAIS BYD** identifica
**somente a disponibilidade de APIs OEM** do DiLink (AVMCamera,
BmmCameraInfo, serviço de câmera BYD, bibliotecas indicativas e pacotes de
visualização). O aparelho poderá estar restringindo o carregamento dessas
classes para APKs comuns. A descoberta é executada em thread separada,
sem abertura da câmera, sem prévia, sem bind, sem gravação de áudio e sem
comandos ou alterações na central.

Projeto de referência: OverDrive, MIT
https://github.com/yash-srivastava/Overdrive-release

Compatibilidade precisa ser comprovada especificamente no Dolphin Mini e
firmware do responsável. A presença da classe AVMCamera não prova que
uma câmera seja acessível a um app comum nem que permita quatro fluxos.

O microfone do Android foi verificado em teste opcional anterior de
5 segundos. A sincronização áudio+vídeo de cabine, início automático,
retorno após reboot, retenção circular e acesso ao vivo só poderão ser
declarados operacionais depois de obtermos frames reais por fonte
autorizada, sem interferir na câmera de ré e com ocupantes informados.
Não existe modo de escuta oculta nem serviço remoto nesse APK.
