# Sharvin Mobile • Aplicativo Android

**Status deste pacote:** projeto Android desenvolvido a partir do Sharvin enviado, **não um APK já compilado ou testado no telefone**. O APK precisa ser gerado usando Android Studio/Android SDK. O ambiente de preparação não tinha Android SDK e não pôde testar o aplicativo em um aparelho.

## O que foi implementado

- App único para a equipe que atende **DRR, 2RR e RMC** (sem cadastro de destinatários por empresa).
- WebView na página original `https://192.168.15.9:3002/index.html`, mantendo os HTML/CSS/JS existentes e o Painel Operacional intactos no servidor.
- Ícone oficial Sharvin, tela de inicialização e carregamento, botão de tentar novamente.
- Acesso à tela somente conectado por Wi-Fi e ao endereço IP privado do Sharvin; **não há servidor público nem banco de dados dentro do APK**.
- HTTPS com certificado público do servidor embarcado para o WebView e verificação adicional do certificado exato na consulta da API; a chave privada **não** está incluída.
- Serviço de primeiro plano que consulta a cada ~15 segundos `GET /api/painel-operacional/state`, recurso já existente no `routes/painelOperacional.js` do ZIP.
- Notificações nativas Android de: avisos novos/urgentes/atualizados/retirados, novos/atualizados/concluídos itens da fila de produção, alterações em contagem/coleta e mudanças dos códigos DRR/2RR/RMC.
- Após a **primeira conexão**, mostra um resumo das pendências já existentes; depois avisa individualmente sobre as mudanças. Quando o telefone fica sem Wi-Fi ou o servidor fica indisponível, o monitor aguarda e compara a última situação com o próximo estado recebido.
- Continua monitorando com a tela fechada enquanto o serviço de primeiro plano estiver ativo; inicia novamente após o reinício do Android quando permitido pelo sistema.
- Notificação de monitoramento contínuo para informar que o serviço está ativo. Android 13+ solicita permissão de notificações.

## Gerar um APK para instalar

1. No Windows, instale o **Android Studio** e o SDK **Platform 34** (Settings > Android SDK); instale também um **JDK 17**.
2. Abra **esta pasta `Sharvin-Mobile-Android`** como projeto no Android Studio; aguarde o Gradle sincronizar (é necessário internet para baixar componentes do Android e Gradle).
3. Menu **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
4. O arquivo gerado é `app/build/outputs/apk/debug/app-debug.apk`. Copie-o aos colaboradores. **Ou**, se possuir Java + Android SDK configurados, dê duplo clique em **`GERAR_APK.bat`**, que tentará gerar `Sharvin-Mobile.apk` nesta pasta.
5. Em cada celular Android, instale o APK autorizando a instalação de origem externa; conecte ao Wi-Fi da empresa e autorize **Notificações**. Na gestão de bateria do aparelho, prefira **Sem restrições** para o Sharvin, especialmente em Samsung, Xiaomi e Motorola, para reduzir encerramentos do serviço.

O APK de depuração é assinado automaticamente pela configuração de debug no computador que compilou. **Para atualizações sobre instalações já feitas, gere as próximas versões com a mesma chave de assinatura.** Para distribuição definitiva, recomenda-se gerar um APK de release assinado com chave própria e guardá-la com segurança.

## Funcionamento, limitações e segurança

- O endereço fixo é `192.168.15.9:3002`. Se o IP mudar, ajuste `SharvinConfig.java`, e gere um novo APK.
- O certificado `app/src/main/res/raw/sharvin_server.pem` corresponde ao recebido no ZIP e expira em **06/10/2028**. Se renovar/trocar o certificado no servidor, atualize também este arquivo e gere um novo APK. Nunca copie a chave `-key.pem` para o aplicativo.
- **Não é necessário alterar, reinstalar ou reiniciar o Sharvin no servidor** para habilitar este app. O app consulta a API existente.
- O app não é um serviço de push instantâneo (FCM): a checagem ocorre a cada ~15 segundos quando o Android permite execução e existe conexão com o servidor local. O Android pode atrasar ou suspender monitoramento em economia de bateria/Doze; eventos criados e removidos entre duas consultas podem não ser vistos. Mudanças ocorridas durante indisponibilidade são comparadas quando a conexão voltar, mas não há histórico persistente de eventos no backend.
- É necessário que o telefone consiga chegar à rede do servidor e tenha Wi-Fi ativo. Isso restringe a operação comum à rede da empresa; para restrição **absoluta** à rede física corporativa, configure também firewall e segmentação de rede no servidor/roteador (não basta a verificação no cliente).
- Para manter o HTTPS validado, o WebView deve confiar no certificado local incluído na configuração de rede. Se um modelo Android rejeitar a configuração da âncora de confiança, deve-se instalar e confiar na CA local do `mkcert` usando configuração adequada, sem desligar a verificação TLS.
- Os recursos que o HTML original busca em CDNs externos (Bootstrap, FontAwesome, Google Fonts etc.) podem precisar de internet, como já acontece no navegador. Dados e operações Sharvin permanecem no servidor local.
- Este projeto **não envia dados a terceiros** e não integra servidores de notificações de terceiros.

## Conteúdo da pasta

- `app/src/main/java/br/com/sharvin/mobile/MainActivity.java`: tela, WebView e abertura das páginas.
- `MonitorService.java`: conexão HTTPS, comparação dos estados e avisos Android.
- `StateDiffer.java`: regras que classificam alterações no painel operacional.
- `BootReceiver.java`: tenta recuperar monitoramento após reiniciar o telefone.
- `res/raw/sharvin_server.pem`: certificado **público** atual do servidor.
- `.github/workflows/android-apk.yml`: alternativa para gerar APK de debug no GitHub Actions após colocar o código em repositório privado.

**Nenhum arquivo `.env`, token de serviço, banco de dados ou chave privada do Sharvin foi incluído neste projeto.**
