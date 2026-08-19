# Storm SSH Android

Cliente Android do SSH Plus Manager preparado para a infraestrutura HostStorm.

## Configuração fixa

- Host: `ssh.hoststorm.cloud`
- Porta: `2222`
- Tipo: SSH com autenticação por senha
- VPN Android: ativa após autenticação SSH

O usuário final só informa **usuário** e **senha** criados pelo SSH Plus Manager e toca em **Conectar**.

## Como funciona

O APK usa uma sessão SSH como transporte e o `VpnService` do Android para encaminhar o tráfego do aparelho pelo túnel. O servidor SSH não precisa oferecer shell interativo; o cliente usa canais SSH `direct-tcpip` para encaminhamento.

## Base técnica

Para não reinventar a pilha TCP/VPN, o build usa como base o projeto MIT `44114/socks5`, fixado no commit:

`b390c792fb345bdc2b9160bc9d6bb067ee0cfb21`

O workflow clona exatamente esse commit e aplica os arquivos da pasta `AndroidClient/overlay/`, substituindo a interface por uma tela Storm SSH com servidor pré-configurado.

A licença MIT original é incluída dentro do APK em `res/raw/third_party_licenses.txt`.

## APK

O GitHub Actions gera um APK Android instalável e publica automaticamente no GitHub Release `storm-ssh-v1.0.0` quando alterações do cliente entram na branch `main`.

Nesta primeira versão de teste o APK é **debug-signed**, adequado para instalação direta e validação do túnel. Para distribuição pública/Play Store, configure depois uma chave de assinatura privada em GitHub Secrets.

## Alterar servidor no futuro

Edite as constantes no arquivo:

`AndroidClient/overlay/app/src/main/java/com/socks5/ui/MainActivity.kt`

```kotlin
private const val SERVER_HOST = "ssh.hoststorm.cloud"
private const val SERVER_PORT = 2222
```
