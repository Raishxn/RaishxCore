# Auditoria técnica — UFO Future e RaishxCore

Data: 2026-09-16
Repositórios auditados:

- `/home/raishxn/MineProjects/UFO-Future-1.21.1` — `0888dbc`, versão `3.0.0-alpha.1`
- `/home/raishxn/MineProjects/RaishxCore` — `07e6c78`, versão `0.1.0-alpha.2`

## Veredito executivo

Os projetos já estão acima da média de mods em testes, preocupação transacional e validação de lifecycle. Não são projetos frágeis: o build está verde, há lint estrito, testes puros, GameTests reais, testes de unload/reload e suites de carga/soak.

Ainda não estão prontos para serem chamados de plataforma profissional estável. O UFO está em condição coerente de **alpha forte**. O RaishxCore é uma **boa prova de fundação**, mas ainda é pequeno, acoplado aos internals do AE2 e não contém boa parte da infraestrutura reutilizável que permanece dentro do UFO.

As cinco prioridades reais são:

1. tornar o planner incapaz de consumir um tick inteiro no thread do servidor e controlar sua memória;
2. concluir a extração do toolkit de multiblocos/transações/sync para o Core;
3. criar CI, publicação e governança próprias para o Core;
4. fechar segurança multiplayer (ownership/claims e rate limit uniforme);
5. formalizar versões e migrações de saves, rede e API.

## Evidência executada

| Verificação | Resultado |
|---|---:|
| `RaishxCore ./gradlew test build --offline` | aprovado |
| `UFO ./gradlew test build --offline` | aprovado |
| testes unitários Core | 39/39, 0 falhas, 0 skips |
| testes unitários UFO | 228/228, 0 falhas, 0 skips |
| GameTests Core | 4/4 aprovados |
| GameTests UFO sem Mekanism | 26/26 aprovados |
| datagen no build UFO | aprovado, worktree permaneceu limpo |
| verificação de integridade ZIP/JAR UFO | aprovada |
| CI remoto do último commit UFO | aprovado |
| CI próprio do Core | inexistente |

O GameTest do UFO cobre unload/reload físico, unload parcial, recuperação do Grid Link, outputs pendentes e migração de saves. Isso é um diferencial importante.

## Benchmark do planner do Core

Medição local com Java 21, 16 CPUs, 10 warmups e 25 amostras:

| Cenário | Fase | p50 | p95 | alocação/operação |
|---|---|---:|---:|---:|
| cadeia 2.048 | grafo em cache | 3,01 ms | 17,02 ms | 1,78 MB |
| cadeia 2.048 | construir grafo + plano | 4,52 ms | 5,27 ms | 3,97 MB |
| cadeia 20.000 | grafo em cache | 21,65 ms | 25,37 ms | 13,71 MB |
| cadeia 20.000 | construir grafo + plano | 42,57 ms | 46,51 ms | 32,41 MB |
| grafo largo 1.025 | grafo em cache | 1,07 ms | 1,42 ms | 1,09 MB |
| grafo largo 1.025 | construir grafo + plano | 2,48 ms | 2,82 ms | 2,35 MB |

Todos os resultados passaram a replay de conservação. A correção está boa; latência e alocação em grafos extremos ainda precisam de trabalho.

Além desse custo assíncrono, `Ae2PlanningSnapshot.capture` roda no thread do servidor e aceita trabalhar até 50 ms, 100 mil arestas e 25 mil chaves antes de desistir. Em cache frio, uma única requisição pode consumir praticamente um tick inteiro **antes** de o cálculo assíncrono começar.

## P0 — antes de declarar estabilidade

### 1. Planner: tirar a captura pesada do tick principal

Estado atual:

- snapshot de AE2 é capturado sincronamente no server thread;
- cache de apenas 16 alvos é invalidado quando a revisão global muda;
- worker pool é global, fixo em 2 threads e fila de 32;
- timeout de 2 s e limites do grafo estão hardcoded;
- ao lotar a fila, a requisição cai no planner do AE2, podendo transferir o pico em vez de aplicar backpressure;
- executor daemon não possui lifecycle explícito por servidor;
- status é apenas o último resultado, não métricas por grid/requisição.

Necessário:

- captura incremental com orçamento por tick, ou snapshot imutável mantido por eventos de mutação;
- deduplicar requests iguais em andamento;
- limites configuráveis: tempo de captura, nós, arestas, heap estimado, fila e workers;
- cancelar por disconnect, unload, fechamento do servidor e revisão obsoleta;
- circuit breaker por grid e fallback observável;
- métricas p50/p95/p99, cache hit, queue depth, declines, timeouts e bytes estimados;
- teste de concorrência com vários grids e requests simultâneos;
- gate de benchmark para impedir regressões relevantes.

### 2. Compatibilidade com AE2 não pode depender só de mixins obrigatórios

O Core injeta diretamente em `CraftingService` e `NetworkCraftingProviders`, incluindo campos internos e múltiplos pontos de `onServerEndTick`. O mixin config é `required=true`/`defaultRequire=1`. Uma mudança compatível em versão pública do AE2 pode ainda quebrar startup.

Necessário:

- matriz CI contra a menor e a maior versão de AE2 suportada;
- testes de contrato sobre cada ponto injetado;
- kill-switch independente para planner e shared CPU pool;
- camada adapter por versão do AE2;
- diagnóstico claro de incompatibilidade antes de corromper jobs;
- restringir o range de AE2 ao que foi realmente testado, não ao que apenas compila.

### 3. Segurança e autoridade multiplayer

Pontos bons: ações de máquina validam menu, posição, distância, chunk carregado e instância atual do block entity.

Falta:

- ownership/equipe/claims para controller, auto-build e ações destrutivas;
- integração opcional com APIs de proteção/claims;
- rate limit para **todos** os C2S; os pacotes de cycle tool/mode, auto-smelt, abertura/configuração de armadura não passam pelo guard central;
- limite de custo, não só frequência: scan e auto-build são mais caros que toggle;
- audit log opcional de ações administrativas/destrutivas;
- testes de pacote malicioso, spam, menu trocado, dimensão errada e logout durante ação.

### 4. Versionamento de saves e migração

Há leitores defensivos e GameTests de saves legados, mas não existe uma camada única de schema/version/migration para todos os BEs e SavedData.

Necessário:

- `dataVersion` por estado persistente;
- migradores sequenciais e idempotentes;
- fixtures douradas de cada release suportado;
- política explícita de downgrade (rejeitar com diagnóstico, nunca interpretar silenciosamente);
- limites de tamanho/contagem para listas e BigIntegers vindos de NBT;
- teste de crash entre reserva, commit e save para outputs/energia/ingredientes.

### 5. Fechar funcionalidade exposta mas inexistente

`PacketChangeSideConfig` está registrado e validado, mas é deliberadamente no-op. Isso deve ser implementado por completo ou removido da UI/protocolo até estar pronto. Funcionalidade fantasma reduz confiança e confunde suporte.

### 6. QA real ainda assumido, não provado

O próprio release note reconhece que ainda faltam:

- abrir e migrar um mundo 2.x completo;
- medir 100+ máquinas no modpack real com jogadores;
- validação visual/interativa das telas e do abastecimento explícito.

Esses itens devem ser gates de release com checklist assinado e evidência anexada, não notas soltas no ledger.

## P1 — tornar o RaishxCore uma plataforma reutilizável

### 1. Extrair o toolkit que ainda está no UFO

Hoje o Core possui 62 classes/2.914 linhas de Java principal; o UFO possui 407 classes/49.023 linhas. A infraestrutura mais valiosa ainda mora no consumidor:

- compilador/matcher de pattern e constraints;
- scanner e resultado detalhado de erros;
- `StructureMembershipIndex` e invalidação por dirty regions;
- orientação/transformação de estruturas;
- auto-build e lifecycle de sessão;
- holograma/highlight/preview compartilhado;
- snapshots e sync delta-budgeted;
- pending output/reservation/journal;
- runtime/cadência de processos paralelos;
- diagnóstico de performance por máquina;
- adapters de ports AE2/NeoForge/Mekanism.

Ordem segura de extração:

1. tipos puros + testes de contrato;
2. scanner compilado e transforms;
3. membership/invalidation;
4. engine de transação persistente;
5. snapshots/sync;
6. auto-build/holograma/viewer;
7. runtime budgetado;
8. adapters opcionais.

Cada recorte precisa manter o UFO como consumidor real antes de apagar a implementação antiga.

### 2. Separar módulos e dependências

O pacote chamado `api` ainda expõe AE2 (`SharedCraftingCpuPool`) e Minecraft (`Vec3`). Isso contradiz parcialmente a promessa de contratos neutros.

Estrutura recomendada:

```text
raishxcore-api        Java puro: amount, tier, graph, transaction, definitions
raishxcore-platform   Minecraft/NeoForge: lifecycle, codecs, capabilities, config
raishxcore-ae2        adapter e mixins AE2
raishxcore-client     widgets, rendering, snapshots de UI
raishxcore-compat-*   Mekanism/JEI/EMI/etc., carregamento opcional
raishxcore-testkit    builders, fake ports, GameTest fixtures e assertions
```

Se múltiplos JARs forem inconvenientes, os limites ainda devem existir como source sets/pacotes e ser verificados por ArchUnit ou regras equivalentes.

### 3. API profissional

Falta:

- política de estabilidade por pacote com `@Experimental`/`@ApiStatus` real;
- baseline de compatibilidade binária (Japicmp/Revapi);
- Javadoc publicado e examples compiláveis;
- SPI/registries em vez de consumers implementarem internals;
- IDs estáveis em vez de ordinals em packets/saves;
- codecs oficiais para amount, tier, plan, snapshots e diagnostics;
- contrato de threading em todas as interfaces;
- contrato de ownership/lifecycle (`close`, invalidate, unload, reload);
- segundo addon de referência antes de congelar API 1.0.

### 4. CI e release próprios do Core

O repositório Core não possui `.github/workflows`. Ele só é verificado indiretamente pelo CI do UFO.

Criar:

- `build.yml`: unit, GameTest, lint, metadata, dedicated server smoke;
- matriz Java 21 + versões AE2 suportadas;
- `benchmark.yml` manual/noturno com histórico;
- `release.yml`: JAR, sources, Javadoc, POM completo, checksums e SBOM;
- publicação em Maven com `group/artifact/version` imutáveis;
- Dependabot/Renovate e dependency review;
- branch protection exigindo os checks do próprio Core.

O POM deve conter nome, descrição, URL, SCM, issues, licença e developers. Os JARs de ambos os mods devem incluir a licença própria e notices/créditos relevantes.

### 5. Engine transacional unificada

O maior diferencial técnico possível para o Core é uma engine única de recursos:

```text
simulate -> reserve -> validate revision -> commit -> persist receipt
                              \-> rollback/refund
```

Ela deve trabalhar com item, fluido, chemical, FE/AE e quantidades exatas; suportar commit parcial, idempotency key, journal persistente, recuperação após crash e fairness entre máquinas. Isso elimina a classe de bugs de dupe/loss e evita que cada addon crie sua própria lógica.

### 6. Scheduler budgetado

Criar um scheduler cooperativo de jobs de máquina:

- orçamento em nanos/operações por tick;
- round-robin com prioridade e anti-starvation;
- jobs pausáveis/canceláveis/persistentes;
- backpressure de output/energia;
- métricas por máquina e tipo de trabalho;
- nenhuma iteração por unidade para quantidades massivas.

Esse componente atenderia planner, auto-build, scans, processamento paralelo e efeitos grandes.

## P1 — UFO Future

### 1. Modularizar controllers grandes

`StellarNexusControllerBE`, `AbstractParallelMultiblockControllerBE` e `DimensionalMatterAssemblerBlockEntity` acumulam formação, energia, thermal, receitas, persistência, sync, UI e lifecycle. Separar em componentes testáveis:

- `StructureComponent`;
- `EnergyComponent`;
- `ThermalComponent`;
- `RecipeRuntime`;
- `PendingOutputJournal`;
- `MachineSnapshotPublisher`;
- `MachineDiagnostics`.

O BE deve coordenar, não conter toda a regra.

### 2. Armadura modular: terminar engenharia e balanceamento

A roadmap diz que a armadura modular é proposta, mas o código já possui 16 módulos, receitas, tela e eventos. Essa divergência documental é séria.

Falta:

- GameTests/testes unitários por módulo e combinações;
- custos/caps configuráveis por servidor/modpack;
- restrições e conflitos de módulos declarativos;
- cooldowns persistentes independentes do game time da dimensão;
- orçamento para buscas de entidades do magnet/cloak;
- clamp de alcance/velocidade alinhado à política do servidor;
- telemetria de consumo e razão de desativação;
- migração dos componentes quando IDs/settings mudarem.

Hoje há alcance extra de até 32 blocos, speed de até +1000%, magnet de 32 e cloak de 128 blocos; isso exige configuração e profiling em multiplayer.

### 3. Configuração data-driven

Custos, caps, tiers, velocidades, energia, calor e limites deveriam usar specs/schema organizados e reload seguro quando possível. O `UFOConfig` ainda contém comentários de scaffold e só cobre parte da mecânica real.

Separar:

- client preferences;
- common gameplay defaults;
- server-authoritative balance/security;
- world-persistent rules que não podem mudar no meio de uma transação.

### 4. Performance do tick

- manter scans orientados por dirty events;
- evitar `getEntitiesOfClass` amplo por jogador sem índice/cadência adaptativa;
- agrupar buffs/efeitos por scheduler, não um conjunto de buscas por módulo;
- cachear capacidades com invalidação correta;
- publicar snapshot somente quando campos visíveis mudarem;
- medir p95/p99, não apenas média;
- testar grids compartilhados, múltiplos jogadores e chunks em fronteira.

### 5. Pacote e artefatos

Problemas confirmados:

- o JAR UFO inclui oito arquivos de `src/generated/resources/.cache` (~96 KiB);
- o JAR é todo `STORED`, ficando em ~8,3 MB; a causa original de corrupção do inflater deve ser isolada, não normalizada para sempre;
- não há sources JAR do UFO;
- licença própria/README/créditos não aparecem no JAR principal;
- o Core local manteve artefato antigo alpha.1 ao lado do alpha.2 (não afeta CI limpo, mas recomenda `clean` no release).

Corrigir exclusões de resources, adicionar `withSourcesJar`, incluir licença/notices e publicar apenas o artefato cuja versão corresponde à tag.

## P2 — estrutura profissional do projeto

Adicionar aos dois repositórios:

- `CONTRIBUTING.md`, `SECURITY.md`, `CODE_OF_CONDUCT.md` se houver comunidade;
- templates de issue/PR e `CODEOWNERS`;
- ADRs curtos para decisões irreversíveis (BigInteger, mixins, planner, save schemas);
- formatter automatizado e análise estática além de `javac -Xlint`;
- cobertura Jacoco por domínio e mutation tests nas regras transacionais críticas;
- dependency locking/verification e ações GitHub fixadas por SHA;
- changelog gerado/validado contra a versão;
- matriz de compatibilidade publicada;
- política de suporte de saves e de versões de dependências;
- release reproducível a partir de clone limpo;
- relatório de licença/SBOM das dependências.

Documentação atualmente divergente:

- `docs/consumer-integration.md` ainda usa `UFO-Core-1.21.1`, artefato `ufocore` e alpha.1; o build real usa `../RaishxCore`, artefato `raishxcore` e alpha.2;
- `docs/ufo-core-plan.md` ainda descreve modid/nome antigos;
- a roadmap afirma “zero GameTests”, mas existem e passaram 26;
- a roadmap trata a armadura modular como proposta, embora já esteja implementada;
- itens marcados pendentes/concluídos não acompanham os commits recentes.

O ledger histórico pode continuar, mas o estado atual precisa vir de documentos curtos e verificáveis: `ARCHITECTURE.md`, `COMPATIBILITY.md`, `MIGRATIONS.md` e issues/milestones.

## Mecânicas diferenciais recomendadas

### Para o RaishxCore

1. **Multiblock Definition DSL/data pack** — uma definição canônica gera scanner, preview, holograma, auto-build, GuideME/JEI e mensagens de erro.
2. **Diagnóstico explicável** — “faltam 3 blocos X nestas posições”, “tier misto”, “chunk não carregado”, “porta duplicada”, com severidade e ações sugeridas.
3. **Transações crash-safe** — journal/idempotência para acabar com dupes e perdas mesmo após crash no meio do tick.
4. **Work scheduler universal** — jobs massivos budgetados e observáveis, reutilizados por todos os addons.
5. **Machine observability** — `/raishxcore diagnose`, dump JSON/JFR marker e overlay com custo/tick, fila, scans, sync e bloqueio atual.
6. **Ownership adapter** — dono/equipe/permissões/claims como política plugável.
7. **Migration framework** — schema registries e fixtures para upgrades seguros entre versões.
8. **Addon testkit** — fake ports/grids, builders de estruturas, assertions de conservação e GameTest templates.
9. **Capability adapter SPI** — AE2, FE, fluid e chemical entram por adapters opcionais, sem contaminar o núcleo matemático.
10. **Data-driven tier curves** — tiers definidos por datapack/config validado, com valores exatos e apresentação consistente.

### Para o UFO Future

1. **Blueprint de estruturas** — capturar uma estrutura válida, projetar em outro lugar, mostrar materiais e delegar ao auto-build seguro.
2. **Painel de operações da rede** — visão agregada de todas as máquinas: throughput, bloqueios, energia, calor, coolant, jobs e gargalos.
3. **Contratos de produção** — jogador pede uma taxa sustentada; o sistema distribui jobs entre máquinas com prioridade/fairness e explica o limitante.
4. **Manutenção baseada em telemetria** — desgaste/falhas previsíveis, nunca RNG punitivo; sensores e redundância recompensam engenharia.
5. **Simulação Stellar dinâmica** — eventos com sinais antecipados, trade-off de risco/resultado e respostas do jogador, todos budgetados.
6. **Progressão por domínio tecnológico** — conhecimento obtido ao operar máquinas desbloqueia parâmetros/blueprints, sem virar grind arbitrário.
7. **Armor loadouts** — presets, conflitos explícitos, orçamento total de energia e troca segura fora de combate.
8. **Terminal portátil UFO** — acesso e diagnóstico remoto sujeito a energia, ownership e alcance/configuração.
9. **Recuperação/repair assistido** — após unload, quebra ou upgrade, mostrar exatamente o que foi preservado e o que precisa de intervenção.
10. **API de integração KubeJS/data pack** — registrar receitas, tiers, coolants e simulações com validação antecipada e mensagens úteis.

## Roadmap de execução recomendado

### Marco A — robustez de release

- corrigir artefatos/licenças/cache;
- CI próprio do Core;
- atualizar documentação contraditória;
- implementar/remover side config;
- aplicar guard/rate limit a todos os C2S;
- concluir QA de mundo 2.x e modpack real.

### Marco B — Core operacional

- snapshot/planner incremental e configurável;
- métricas/circuit breaker/lifecycle;
- matriz AE2;
- schema/migrations;
- ownership SPI;
- engine transacional.

### Marco C — extração do toolkit

- pattern/scanner/orientation;
- membership/invalidation;
- sync/snapshots;
- auto-build/holograma/viewer;
- scheduler e diagnostics;
- UFO convertido para consumidor, sem duplicação.

### Marco D — validação como plataforma

- criar um segundo addon pequeno usando somente APIs públicas;
- publicar testkit e exemplo;
- rodar compatibilidade binária;
- corrigir a API a partir desse consumo;
- somente então declarar RaishxCore 1.0.

### Marco E — diferenciais de gameplay

- painel de operações;
- blueprints;
- production contracts;
- armor loadouts/configuração;
- novas simulações e progressão, apoiadas na fundação já medida.

## Critério de “pronto profissional”

O estado desejado estará provado quando:

- clone limpo reproduzir artefatos e checksums;
- Core e UFO tiverem CI próprio verde e matriz compatível;
- nenhum C2S escapar de validação, ownership e rate budget;
- todo estado persistente tiver schema/migração/fixture;
- planner e máquinas respeitarem orçamento de tick e memória sob carga concorrente;
- JARs contiverem metadata/licença corretas e nenhum cache interno;
- API pura não vazar tipos de plataforma sem ser um adapter declarado;
- segundo addon consumir o Core sem copiar código nem acessar internals;
- mundo legado e modpack real passarem o roteiro de release;
- documentação descrever o código atual, não uma fase histórica.
