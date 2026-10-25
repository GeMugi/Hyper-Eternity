# Hyper Eternity

Módulo LSPosed para **HyperOS 3 / HyperOS 4** (Android 16/17) que muda as notificações, a heads-up, a tela de bloqueio e o player.
*LSPosed module for HyperOS 3 / 4 that reworks notifications, heads-up, lock screen and the media player.*

**Ideia, design e direção:** Fabulous Lightning McQ — [@Gmugi](https://t.me/GmugiUwu) · canal Telegram **Gmugi 愛**
*Concept, design and direction by Fabulous Lightning McQ (@Gmugi).*

## Funcionalidades / Features

| | PT | EN |
|---|---|---|
| Heads-up | Animação estilo OriginOS (nasce da ilha) ou iOS (sai do ícone da barra), com mola, velocidade e bounce | OriginOS / iOS style heads-up with spring animation |
| Grupos em pilha | Notificações agrupadas viram uma pilha; toque espalha, sub-pilha por conversa, sombra, centralização; HyperOS 3 e 4 | Grouped notifications as stacks with per-conversation sub-stacks (HyperOS 3 & 4) |
| Agrupamento inteligente | Agrupa a partir de 2; conversas (Telegram/Instagram) também; aprende sozinho apps com "um grupo por notificação" (ex.: Duolingo) | Smart grouping from 2, auto-learns one-group-per-notification apps |
| Conversas | Cada mensagem num cartão; só o nome de quem mandou em grupos; hora no canto | One card per message, sender-only titles |
| Pilha da gaveta | Empilhamento no fim da gaveta como no HyperOS 4, com curva suave | Bottom-of-shade stacking with smooth curve |
| Mensagem inteira | Ao segurar a notificação, o cartão mostra a mensagem completa com rolagem | Full message on long-press, scrollable |
| Mais linhas | 2–10 linhas na central; 3 dentro de pilhas | Configurable lines in shade |
| Privacidade | Desfoque por app, conversa ou contato (inclusive grupos) | Per-app/conversation/contact blur |
| Pílulas do relógio | Vermelho (gravação), azul (localização), verde (roteador), animadas | Status bar clock pills: rec / location / hotspot |
| Player | Vibrancy estilo iOS, capa que gira e brilha, título com pontas desfocadas | iOS-style vibrancy player |
| Bloqueio | Empilhar, rolar sem desbloquear, relógio que acompanha, desfoque do topo | Lock screen stacking & scrolling |
| App | Tela de ajustes em miuix: fundo animado, vidro fosco, barra flutuante | miuix settings app |

## Estrutura / Layout

- **Hooks** (`src/`, `apistubs/`) — o módulo LSPosed em Java: heads-up, pilhas, bloqueio, cortina, player. *LSPosed hooks (Java).*
- **App de ajustes** (`miuix-app/ilha/`) — a tela de ajustes feita com [miuix](https://github.com/compose-miuix-ui/miuix) (Kotlin/Compose), no mesmo pacote `com.ilha.headsup`. *Settings app built with miuix.*
- **HyperMusicCover-Enhanced (extensão)** — opcional, APK separado: capa e letras no bloqueio. *Optional extension, separate APK.*

## Créditos / Credits

- **Fabulous Lightning McQ (@Gmugi)** — autor, ideias e testes.
- A extensão usa o [HyperMusicCover-Enhanced](https://github.com/Lewewe/HyperMusicCover-Enhanced) de Lewewe, baseado no [HyperMusicCover](https://github.com/zyl6932/HyperMusicCover) de zyl6932 (AGPL-3.0).
- Tela de ajustes feita com [miuix](https://github.com/compose-miuix-ui/miuix) (Apache 2.0).
- Desenvolvimento assistido por Claude (Anthropic).

## Licença / License

GNU AGPL v3.0 — veja [LICENSE](LICENSE). Quem distribuir versões modificadas deve manter os créditos e publicar o código-fonte.

## Compilar / Build

Veja `LEIA-ME.txt`. *See `LEIA-ME.txt`.*
