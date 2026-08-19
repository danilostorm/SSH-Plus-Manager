# Storm SSH v2

Cliente Android do SSH Plus Manager.

## Servidor embutido

- Host: `ssh.hoststorm.cloud`
- Porta: `2222`
- Login: usuário e senha criados pelo SSH Plus Manager

## Arquitetura

A v2 troca o encaminhador TCP experimental da primeira versão por um pipeline de VPN mais robusto:

1. JSch autentica no SSH Plus Manager.
2. O app abre um SOCKS5 local em `127.0.0.1:1080` usando canais SSH `direct-tcpip`.
3. `hev-socks5-tunnel`/tun2socks recebe o TUN do Android e encaminha o tráfego TCP para o SOCKS local.
4. MapDNS do tun2socks resolve nomes sem depender de DNS UDP externo.
5. O serviço roda em foreground e continua conectado com o app minimizado.

## Interface

A interface Storm possui perfil de rede/operadora, usuário, senha, reconexão, detalhes, servidor e atalhos para hotspot, modo avião, APN, rede, bateria e configurações do app.

Os perfis de operadora ajustam parâmetros de rede (como MTU). Eles não alteram ou burlam cobrança da operadora.

## Créditos/licenças

- SocksTun / hev-socks5-tunnel: MIT, Copyright (c) 2023 hev.
- JSch fork by mwiede: BSD-style license.
- O repositório de referência `TelksBr/SSH_T_PROJECT_VPN` declara licença MIT, porém a árvore pública e o ZIP 5.0.0 disponibilizados contêm somente `README.md` e `LICENSE`; por isso a v2 não depende do APK fechado e usa componentes open-source auditáveis.
