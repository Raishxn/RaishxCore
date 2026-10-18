# Auditoria de desempenho — RaishxCore

Data: 2026-10-18 · base: `59e9a64` (0.2.1) · mudanças **sem commit**.
Relatório irmão (consumidor): `../UFO-Future-1.21.1/docs/auditoria-desempenho.md`.

Escopo: tudo menos o algoritmo do `IterativeCraftingPlanner`, já otimizado e medido na sessão
anterior (`docs/planner-head-to-head.md`, baseline do `plannerBenchmark` regravada). As mudanças
dessa sessão (planner, `CoreConfig`, `ROADMAP`, `CHANGELOG`, `build.gradle`) não foram tocadas.

## Resumo

O ponto quente fora do planner é o mixin `SharedCraftingCpuServiceMixin`: ele se aplica a
**todo** `CraftingService` do servidor (um por grid AE2), não só aos grids com Nexus. Duas coisas
custavam a cada tick:

1. com qualquer job ativo num pool compartilhado, o mixin forçava o AE2 a reconstruir
   `currentlyCrafting` em **todo** tick;
2. a lista de pools distintos era montada de novo (um `IdentityHashMap` novo) em cada chamada,
   inclusive nos grids sem pool, duas vezes por tick e em cada inserção na rede, consulta de
   requisição e checagem de link.

As duas foram corrigidas; o resto do Core está sob controle.

| Verificação | Resultado |
|---|---:|
| `./gradlew test` | 229/229 |
| `./gradlew runGameTestServer` (Core) | 14/14 |
| `./gradlew runGameTestServer` no UFO (usa este working tree via `includeBuild`; Nexus parallel crafting, compat de CPU, reload) | 57/57 |

Não houve medição em jogo; os ganhos são contagens de operações tiradas do código e do AE2 19.2.x.

## Achados priorizados

| # | Onde | Custo e frequência | Impacto | Risco | Estado |
|---|---|---|---|---|---|
| 1 | `SharedCraftingCpuServiceMixin#raishxcore$tickPools` (`mixin/SharedCraftingCpuServiceMixin.java:118`) | `lastProcessedCraftingLogicChangeTick = -1` sempre que um pool retornava um tick válido, ou seja, **todo tick com job ativo**. O AE2 então copiava `currentlyCrafting` para um `HashSet`, limpava, refazia `getAllWaitingFor` de todas as CPUs (as do AE2 e as do pool), calculava as duas diferenças e notificava watchers. Custo O(chaves aguardadas) por tick, por grid com Nexus ocupado. | A | B–M | **feito** |
| 2 | `raishxcore$uniquePools()` (`:270`) | Novo set de identidade a cada chamada. Chamado em `onServerEndTick` (2×/tick em **todos** os grids), `getCpus`, `insertIntoCpus` (cada inserção na rede via `CraftingServiceStorage`), `getRequestedAmount`, `submitJob` e `hasCpu` (cada crafting link, todo tick). | M | B | **feito** |
| 3 | `raishxcore$rescanPools` (`:95`) | `ArrayList.contains` dentro de `removeIf` → O(pools × nós da grid) a cada `updateCPUClusters`. | B | B | **feito** |
| 4 | `SharedCraftingCpuPool#containsCpu` (padrão) | Chamado por `hasCpu` para cada link, todo tick; o padrão itera `getActiveCpus()`, que no Nexus aloca uma lista. | M | B | **feito no UFO** (override sem alocação); padrão mantido |
| 5 | `raishxcore$refreshDynamicPools` (`:109`) | `List.copyOf(providerNodes)` + `getService` por nó provedor, todo tick. Grids sem provedor recebem a lista vazia compartilhada (custo ~0); com provedor são poucos nós. | B | — | sem ação |
| 6 | `raishxcore$rescanPools` | Percorre todos os nós de máquina do grid a cada `updateCPUClusters` (raro: muda quando a lista de CPUs muda). Em grids de dezenas de milhares de nós é um pico pontual. | B | M | proposta P2 |
| 7 | Captura do grafo / planner em jogo | Já fatiada e com orçamento: 2 ms por fatia, orçamento global por tick, cache por revisão, deduplicação de requests, circuit breaker (ver `CoreConfig` e `docs/planner-cooperative-capture.md`). O cálculo roda em worker. | — | — | sem ação nesta auditoria |
| 8 | `MultiblockInteriorRenderer` | Geometria procedural imediata (`RenderType.lightning`) por frame, poucos quads, distância 256. | B | — | sem ação |
| 9 | `UfoMachinePacketGuard` | Checagem de menu, distância e rate limit por pacote C2S; barato. | — | — | sem ação |

## O que foi mudado (`mixin/SharedCraftingCpuServiceMixin.java`)

1. **Reconstrução de `currentlyCrafting` só quando um job do pool muda.** O mixin guarda o último
   estado que causou a reconstrução: o maior `getLastModifiedOnTick()` entre os jobs do pool
   (retorno de `tickCraftingLogic` mais o de cada `CraftingCPUCluster` em `getActiveCpus()`, porque o
   Nexus pode tickar só parte dos jobs e os demais ainda mudam ao receber saídas) e o número de CPUs
   de job. Só força o AE2 a recalcular quando um desses muda — é o mesmo sinal que o AE2 usa para as
   próprias CPUs (`postChange` em toda alteração de `waitingFor`). A criação e o fim de jobs mudam a
   contagem e também sinalizam `consumeCpuListChanged` → `updateCPUClusters`, que já força o recálculo.
   Grids sem pool saem cedo, sem alocar.
2. **Cache da lista de pools distintos.** `raishxcore$uniquePools()` devolve uma lista imutável
   reconstruída só quando `raishxcore$pools` muda (os três pontos de mutação a invalidam); grid sem pool
   recebe `List.of()`. A ordem é a mesma que um set novo teria com o mesmo conteúdo.
3. **Rescan com set de identidade** para a pertinência, em vez de `ArrayList.contains`.

Compatibilidade: nenhuma assinatura de injeção mudou (alvos do AE2 19.2.18 iguais). A interface
`SharedCraftingCpuPool` não mudou.

## Propostas pendentes (precisam de confirmação)

- **P1 — Documentar o contrato de `tickCraftingLogic`.** O mixin agora depende de o pool refletir
  mudanças de jobs pelo valor retornado ou por `getActiveCpus()` com `CraftingCPUCluster`. Vale escrever
  isso no javadoc de `SharedCraftingCpuPool` (mudança de API pública só em texto; não apliquei porque a
  documentação da API é sua decisão).
- **P2 — Rescan incremental.** Em `updateCPUClusters`, revisitar só os nós já conhecidos como provedores
  mais os adicionados desde o último rescan (`addNode` já registra), em vez de todos os nós de máquina.
- **P3 — Medição em jogo.** Perfil com spark num grid com Nexus executando um job grande (milhares de
  chaves aguardadas) antes/depois do item 1, e o `PlannerCaptureSoakGameTests` com métricas de tick.
